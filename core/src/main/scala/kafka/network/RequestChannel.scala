package kafka.network

import java.nio.ByteBuffer
import java.util.concurrent._
import com.fasterxml.jackson.databind.JsonNode
import com.typesafe.scalalogging.Logger
import kafka.network
import kafka.server.KafkaConfig
import kafka.utils.Logging
import org.apache.kafka.common.config.ConfigResource
import org.apache.kafka.common.memory.MemoryPool
import org.apache.kafka.common.message.EnvelopeResponseData
import org.apache.kafka.common.network.Send
import org.apache.kafka.common.protocol.{ApiKeys, Errors}
import org.apache.kafka.common.requests._
import org.apache.kafka.common.utils.Time
import org.apache.kafka.network.Session
import org.apache.kafka.network.metrics.{RequestChannelMetrics, RequestMetrics}
import org.apache.kafka.server.common.RequestLocal
import org.apache.kafka.server.metrics.KafkaMetricsGroup
import org.apache.kafka.network.RequestConvertToJson

import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters.RichOption
import scala.reflect.ClassTag

object RequestChannel extends Logging {
  private val requestLogger = Logger("kafka.request.logger") // 출력값이 뭐가 나올까?

  private val RequestQueueSizeMetric = "RequestQueueSize"
  private val ResponseQueueSizeMetric = "ResponseQueueSize"
  val ProcessorMetricTag = "processor"

  private def isRequestLoggingEnabled: Boolean = requestLogger.underlying.isDebugEnabled

  sealed trait BaseRequest // 추상 트레잇: 요청의 종류에 관계없이 동일한 방식으로 처리
  case object ShutdownRequest extends BaseRequest // 서버 종료 요청
  case object WakeupRequest extends BaseRequest // 서버 시작 요청

  case class CallbackRequest(fun: RequestLocal => Unit,
                             originalRequest: Request) extends BaseRequest

  // KafkaRequestHandler.scala에 정의된 trait ApiRequestHandler의 handle 메소드가 해당 클래스 타입을 인자로 받는다. 
  // Request class의 인자 설명
  // 1. processor: 요청을 처리하는 프로세스 ID
  // 2. context: 요청을 처리하는데 필요한 데이터
  // 3. startTimeNanos: 요청이 시작된 시간을 나노초 단위로 설정
  // 4. memoryPool: 요청 처리 중 사용 될 메모리 공간을 관리하는데 사용
  // 5. buffer: 요청데이터를 저장하거나 전송할 때 사용
  // 6. metrics: 요청 처리의 성능 지표를 확인하는데 사용
  // 7. envelope: 값이 없는 경우 null로 표시
  class Request(val processor: Int,
                val context: RequestContext,
                val startTimeNanos: Long,
                val memoryPool: MemoryPool,
                @volatile var buffer: ByteBuffer,
                metrics: RequestChannelMetrics,
                val envelope: Option[RequestChannel.Request] = None) extends BaseRequest {
    // These need to be volatile because the readers are in the network thread and the writers are in the request
    // handler threads or the purgatory threads
    @volatile var requestDequeueTimeNanos: Long = -1L // 요청이 큐에서 꺼내지는 시간
    @volatile var apiLocalCompleteTimeNanos: Long = -1L // API 호출이 로컬에서 완료된 시간
    @volatile var responseCompleteTimeNanos: Long = -1L // 응답이 완료된 시간
    @volatile var responseDequeueTimeNanos: Long = -1L // 응답이 큐에서 꺼내지는 시간
    @volatile var messageConversionsTimeNanos: Long = 0L // 메시지가 다른 형식으로 변환하거나 인코딩, 디코딩할 때 걸린 시간
    @volatile var apiThrottleTimeMs: Long = 0L // API 호출이 지연된 시간
    @volatile var temporaryMemoryBytes: Long = 0L // 일시적으로 사용된 메모리의 크기: 소비된 메모리 용량 추적
    @volatile var recordNetworkThreadTimeCallback: Option[java.util.function.Consumer[java.lang.Long]] = None // 네트워크 스레드에서 소요된 시간을 추적하고, 이를 처리하는 콜백 함수를 저장
    @volatile var callbackRequestDequeueTimeNanos: Option[Long] = None // 콜백요청이 큐에서 꺼내지는 시간
    @volatile var callbackRequestCompleteTimeNanos: Option[Long] = None // 콜백요청이 완료된 시간

    val session: Session = new Session(context.principal, context.clientAddress)

    private val bodyAndSize: RequestAndSize = context.parseRequest(buffer)

    // This is constructed on creation of a Request so that the JSON representation is computed before the request is
    // processed by the api layer. Otherwise, a ProduceRequest can occur without its data (ie. it goes into purgatory).
    val requestLog: Option[JsonNode] =
      if (RequestChannel.isRequestLoggingEnabled) Some(RequestConvertToJson.request(loggableRequest))
      else None

    def header: RequestHeader = context.header

    private def sizeOfBodyInBytes: Int = bodyAndSize.size

    def sizeInBytes: Int = header.size + sizeOfBodyInBytes

    //most request types are parsed entirely into objects at this point. for those we can release the underlying buffer.
    //some (like produce, or any time the schema contains fields of types BYTES or NULLABLE_BYTES) retain a reference
    //to the buffer. for those requests we cannot release the buffer early, but only when request processing is done.
    if (!header.apiKey.requiresDelayedAllocation) {
      releaseBuffer()
    }

    def isForwarded: Boolean = envelope.isDefined

    private def shouldReturnNotController(response: AbstractResponse): Boolean = {
      response match {
        case _: DescribeQuorumResponse => response.errorCounts.containsKey(Errors.NOT_LEADER_OR_FOLLOWER)
        case _ => response.errorCounts.containsKey(Errors.NOT_CONTROLLER)
      }
    }

    def buildResponseSend(abstractResponse: AbstractResponse): Send = {
      envelope match {
        case Some(request) =>
          val envelopeResponse = if (shouldReturnNotController(abstractResponse)) {
            // Since it's a NOT_CONTROLLER error response, we need to make envelope response with NOT_CONTROLLER error
            // to notify the requester (i.e. NodeToControllerRequestThread) to update active controller
            new EnvelopeResponse(new EnvelopeResponseData()
              .setErrorCode(Errors.NOT_CONTROLLER.code()))
          } else {
            val responseBytes = context.buildResponseEnvelopePayload(abstractResponse)
            new EnvelopeResponse(responseBytes, Errors.NONE)
          }
          request.context.buildResponseSend(envelopeResponse)
        case None =>
          context.buildResponseSend(abstractResponse)
      }
    }

    def responseNode(response: AbstractResponse): Option[JsonNode] = {
      if (RequestChannel.isRequestLoggingEnabled)
        Some(RequestConvertToJson.response(response, context.apiVersion))
      else
        None
    }

    def headerForLoggingOrThrottling(): RequestHeader = {
      envelope match {
        case Some(request) =>
          request.context.header
        case None =>
          context.header
      }
    }

    def requestDesc(details: Boolean): String = {
      val forwardDescription = envelope.map { request =>
        s"Forwarded request: ${request.context} "
      }.getOrElse("")
      s"$forwardDescription$header -- ${loggableRequest.toString(details)}"
    }

    def body[T <: AbstractRequest](implicit classTag: ClassTag[T]): T = {
      bodyAndSize.request match {
        case r: T => r
        case r =>
          throw new ClassCastException(s"Expected request with type ${classTag.runtimeClass}, but found ${r.getClass}")
      }
    }

    def loggableRequest: AbstractRequest = {

      bodyAndSize.request match {
        case alterConfigs: AlterConfigsRequest =>
          val newData = alterConfigs.data().duplicate()
          newData.resources().forEach(resource => {
            val resourceType = ConfigResource.Type.forId(resource.resourceType())
            resource.configs().forEach(config => {
              config.setValue(KafkaConfig.loggableValue(resourceType, config.name(), config.value()))
            })
          })
          new AlterConfigsRequest(newData, alterConfigs.version())

        case alterConfigs: IncrementalAlterConfigsRequest =>
          val newData = alterConfigs.data().duplicate()
          newData.resources().forEach(resource => {
            val resourceType = ConfigResource.Type.forId(resource.resourceType())
            resource.configs().forEach(config => {
              config.setValue(KafkaConfig.loggableValue(resourceType, config.name(), config.value()))
            })
          })
          new IncrementalAlterConfigsRequest.Builder(newData).build(alterConfigs.version())

        case _ =>
          bodyAndSize.request
      }
    }

    trace(s"Processor $processor received request: ${requestDesc(true)}")

    def requestThreadTimeNanos: Long = {
      if (apiLocalCompleteTimeNanos == -1L) apiLocalCompleteTimeNanos = Time.SYSTEM.nanoseconds
      math.max(apiLocalCompleteTimeNanos - requestDequeueTimeNanos, 0L)
    }

    def updateRequestMetrics(networkThreadTimeNanos: Long, response: Response): Unit = {
      val endTimeNanos = Time.SYSTEM.nanoseconds

      /**
       * Converts nanos to millis with micros precision as additional decimal places in the request log have low
       * signal to noise ratio. When it comes to metrics, there is little difference either way as we round the value
       * to the nearest long.
       */
      def nanosToMs(nanos: Long): Double = {
        val positiveNanos = math.max(nanos, 0)
        TimeUnit.NANOSECONDS.toMicros(positiveNanos).toDouble / TimeUnit.MILLISECONDS.toMicros(1)
      }

      val requestQueueTimeMs = nanosToMs(requestDequeueTimeNanos - startTimeNanos)
      val callbackRequestTimeNanos = callbackRequestCompleteTimeNanos.getOrElse(0L) - callbackRequestDequeueTimeNanos.getOrElse(0L)
      val apiLocalTimeMs = nanosToMs(apiLocalCompleteTimeNanos - requestDequeueTimeNanos + callbackRequestTimeNanos)
      val apiRemoteTimeMs = nanosToMs(responseCompleteTimeNanos - apiLocalCompleteTimeNanos - callbackRequestTimeNanos)
      val responseQueueTimeMs = nanosToMs(responseDequeueTimeNanos - responseCompleteTimeNanos)
      val responseSendTimeMs = nanosToMs(endTimeNanos - responseDequeueTimeNanos)
      val messageConversionsTimeMs = nanosToMs(messageConversionsTimeNanos)
      val totalTimeMs = nanosToMs(endTimeNanos - startTimeNanos)
      val overrideMetricNames =
        if (header.apiKey == ApiKeys.FETCH) {
          val specifiedMetricName =
            if (body[FetchRequest].isFromFollower) RequestMetrics.FOLLOW_FETCH_METRIC_NAME
            else RequestMetrics.CONSUMER_FETCH_METRIC_NAME
          Seq(specifiedMetricName, header.apiKey.name)
        } else if (header.apiKey == ApiKeys.ADD_PARTITIONS_TO_TXN && body[AddPartitionsToTxnRequest].allVerifyOnlyRequest) {
            Seq(RequestMetrics.VERIFY_PARTITIONS_IN_TXN_METRIC_NAME)
        } else {
          Seq(header.apiKey.name)
        }
      overrideMetricNames.foreach { metricName =>
        val m = metrics(metricName)
        m.requestRate(header.apiVersion).mark()
        m.deprecatedRequestRate(header.apiKey, header.apiVersion, context.clientInformation).ifPresent(_.mark())
        m.requestQueueTimeHist.update(Math.round(requestQueueTimeMs))
        m.localTimeHist.update(Math.round(apiLocalTimeMs))
        m.remoteTimeHist.update(Math.round(apiRemoteTimeMs))
        m.throttleTimeHist.update(apiThrottleTimeMs)
        m.responseQueueTimeHist.update(Math.round(responseQueueTimeMs))
        m.responseSendTimeHist.update(Math.round(responseSendTimeMs))
        m.totalTimeHist.update(Math.round(totalTimeMs))
        m.requestBytesHist.update(sizeOfBodyInBytes)
        m.messageConversionsTimeHist.ifPresent(_.update(Math.round(messageConversionsTimeMs)))
        m.tempMemoryBytesHist.ifPresent(_.update(temporaryMemoryBytes))
      }

      // Records network handler thread usage. This is included towards the request quota for the
      // user/client. Throttling is only performed when request handler thread usage
      // is recorded, just before responses are queued for delivery.
      // The time recorded here is the time spent on the network thread for receiving this request
      // and sending the response. Note that for the first request on a connection, the time includes
      // the total time spent on authentication, which may be significant for SASL/SSL.
      recordNetworkThreadTimeCallback.foreach(record => record.accept(networkThreadTimeNanos))

      if (isRequestLoggingEnabled) {
        val desc = RequestConvertToJson.requestDescMetrics(header, requestLog.toJava, response.responseLog.toJava,
          context, session, isForwarded,
          totalTimeMs, requestQueueTimeMs, apiLocalTimeMs,
          apiRemoteTimeMs, apiThrottleTimeMs, responseQueueTimeMs,
          responseSendTimeMs, temporaryMemoryBytes,
          messageConversionsTimeMs)
        requestLogger.debug("Completed request:" + desc.toString)
      }
    }

    def releaseBuffer(): Unit = {
      envelope match {
        case Some(request) =>
          request.releaseBuffer()
        case None =>
          if (buffer != null) {
            memoryPool.release(buffer)
            buffer = null
          }
      }
    }

    def setRecordNetworkThreadTimeCallback(callback: java.util.function.Consumer[java.lang.Long]): Unit = {
      recordNetworkThreadTimeCallback = Some(callback)
    }

    override def toString: String = s"Request(processor=$processor, " +
      s"connectionId=${context.connectionId}, " +
      s"session=$session, " +
      s"listenerName=${context.listenerName}, " +
      s"securityProtocol=${context.securityProtocol}, " +
      s"buffer=$buffer, " +
      s"envelope=$envelope)"

  }

  sealed abstract class Response(val request: Request) {

    def processor: Int = request.processor

    def responseLog: Option[JsonNode] = None

    def onComplete: Option[Send => Unit] = None
  }

  /** responseLogValue should only be defined if request logging is enabled */
  class SendResponse(request: Request,
                     val responseSend: Send,
                     val responseLogValue: Option[JsonNode],
                     val onCompleteCallback: Option[Send => Unit]) extends Response(request) {
    override def responseLog: Option[JsonNode] = responseLogValue

    override def onComplete: Option[Send => Unit] = onCompleteCallback

    override def toString: String =
      s"Response(type=Send, request=$request, send=$responseSend, asString=$responseLogValue)"
  }

  class NoOpResponse(request: Request) extends Response(request) {
    override def toString: String =
      s"Response(type=NoOp, request=$request)"
  }

  class CloseConnectionResponse(request: Request) extends Response(request) {
    override def toString: String =
      s"Response(type=CloseConnection, request=$request)"
  }

  class StartThrottlingResponse(request: Request) extends Response(request) {
    override def toString: String =
      s"Response(type=StartThrottling, request=$request)"
  }

  class EndThrottlingResponse(request: Request) extends Response(request) {
    override def toString: String =
      s"Response(type=EndThrottling, request=$request)"
  }
}

class RequestChannel(val queueSize: Int,
                     val metricNamePrefix: String,
                     time: Time,
                     val metrics: RequestChannelMetrics) {
  import RequestChannel._

  private val metricsGroup = new KafkaMetricsGroup(this.getClass)

  private val requestQueue = new ArrayBlockingQueue[BaseRequest](queueSize)
  private val processors = new ConcurrentHashMap[Int, Processor]()
  private val requestQueueSizeMetricName = metricNamePrefix.concat(RequestQueueSizeMetric)
  private val responseQueueSizeMetricName = metricNamePrefix.concat(ResponseQueueSizeMetric)
  private val callbackQueue = new ArrayBlockingQueue[BaseRequest](queueSize)

  metricsGroup.newGauge(requestQueueSizeMetricName, () => requestQueue.size)

  metricsGroup.newGauge(responseQueueSizeMetricName, () => {
    processors.values.asScala.foldLeft(0) {(total, processor) =>
      total + processor.responseQueueSize
    }
  })

  def addProcessor(processor: Processor): Unit = {
    if (processors.putIfAbsent(processor.id, processor) != null)
      warn(s"Unexpected processor with processorId ${processor.id}")

    metricsGroup.newGauge(responseQueueSizeMetricName, () => processor.responseQueueSize,
      Map(ProcessorMetricTag -> processor.id.toString).asJava)
  }

  def removeProcessor(processorId: Int): Unit = {
    processors.remove(processorId)
    metricsGroup.removeMetric(responseQueueSizeMetricName, Map(ProcessorMetricTag -> processorId.toString).asJava)
  }

  /** Send a request to be handled, potentially blocking until there is room in the queue for the request */
  def sendRequest(request: RequestChannel.Request): Unit = {
    requestQueue.put(request)
  }

  def closeConnection(
    request: RequestChannel.Request,
    errorCounts: java.util.Map[Errors, Integer]
  ): Unit = {
    // This case is used when the request handler has encountered an error, but the client
    // does not expect a response (e.g. when produce request has acks set to 0)
    updateErrorMetrics(request.header.apiKey, errorCounts.asScala)
    sendResponse(new RequestChannel.CloseConnectionResponse(request))
  }

  def sendResponse(
    request: RequestChannel.Request,
    response: AbstractResponse,
    onComplete: Option[Send => Unit]
  ): Unit = {
    updateErrorMetrics(request.header.apiKey, response.errorCounts.asScala)
    sendResponse(new RequestChannel.SendResponse(
      request,
      request.buildResponseSend(response),
      request.responseNode(response),
      onComplete
    ))
  }

  def sendNoOpResponse(request: RequestChannel.Request): Unit = {
    sendResponse(new network.RequestChannel.NoOpResponse(request))
  }

  def startThrottling(request: RequestChannel.Request): Unit = {
    sendResponse(new RequestChannel.StartThrottlingResponse(request))
  }

  def endThrottling(request: RequestChannel.Request): Unit = {
    sendResponse(new EndThrottlingResponse(request))
  }

  /** Send a response back to the socket server to be sent over the network */
  private[network] def sendResponse(response: RequestChannel.Response): Unit = {
    if (isTraceEnabled) {
      val requestHeader = response.request.headerForLoggingOrThrottling()
      val message = response match {
        case sendResponse: SendResponse =>
          s"Sending ${requestHeader.apiKey} response to client ${requestHeader.clientId} of ${sendResponse.responseSend.size} bytes."
        case _: NoOpResponse =>
          s"Not sending ${requestHeader.apiKey} response to client ${requestHeader.clientId} as it's not required."
        case _: CloseConnectionResponse =>
          s"Closing connection for client ${requestHeader.clientId} due to error during ${requestHeader.apiKey}."
        case _: StartThrottlingResponse =>
          s"Notifying channel throttling has started for client ${requestHeader.clientId} for ${requestHeader.apiKey}"
        case _: EndThrottlingResponse =>
          s"Notifying channel throttling has ended for client ${requestHeader.clientId} for ${requestHeader.apiKey}"
      }
      trace(message)
    }

    response match {
      // We should only send one of the following per request
      case _: SendResponse | _: NoOpResponse | _: CloseConnectionResponse =>
        val request = response.request
        val timeNanos = time.nanoseconds()
        request.responseCompleteTimeNanos = timeNanos
        if (request.apiLocalCompleteTimeNanos == -1L)
          request.apiLocalCompleteTimeNanos = timeNanos
        // If this callback was executed after KafkaApis returned we will need to adjust the callback completion time here.
        if (request.callbackRequestDequeueTimeNanos.isDefined && request.callbackRequestCompleteTimeNanos.isEmpty)
          request.callbackRequestCompleteTimeNanos = Some(time.nanoseconds())
      // For a given request, these may happen in addition to one in the previous section, skip updating the metrics
      case _: StartThrottlingResponse | _: EndThrottlingResponse => ()
    }

    val processor = processors.get(response.processor)
    // The processor may be null if it was shutdown. In this case, the connections
    // are closed, so the response is dropped.
    if (processor != null) {
      processor.enqueueResponse(response)
    }
  }

  /** Get the next request or block until specified time has elapsed
   *  Check the callback queue and execute first if present since these
   *  requests have already waited in line. */
  def receiveRequest(timeout: Long): RequestChannel.BaseRequest = {
    val callbackRequest = callbackQueue.poll()
    if (callbackRequest != null)
      callbackRequest
    else {
      val request = requestQueue.poll(timeout, TimeUnit.MILLISECONDS)
      request match {
        case WakeupRequest => callbackQueue.poll()
        case _ => request
      }
    }
  }

  /** Get the next request or block until there is one */
  def receiveRequest(): RequestChannel.BaseRequest =
    requestQueue.take()

  def updateErrorMetrics(apiKey: ApiKeys, errors: collection.Map[Errors, Integer]): Unit = {
    errors.foreachEntry { (error, count) =>
      metrics(apiKey.name).markErrorMeter(error, count)
    }
  }

  def clear(): Unit = {
    requestQueue.clear()
    callbackQueue.clear()
  }

  def shutdown(): Unit = {
    clear()
    metrics.close()
  }

  def sendShutdownRequest(): Unit = requestQueue.put(ShutdownRequest)

  def sendCallbackRequest(request: CallbackRequest): Unit = {
    callbackQueue.put(request)
    if (!requestQueue.offer(RequestChannel.WakeupRequest))
      trace("Wakeup request could not be added to queue. This means queue is full, so we will still process callback.")
  }

}
