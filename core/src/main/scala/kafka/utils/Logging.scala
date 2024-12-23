package kafka.utils

import com.typesafe.scalalogging.Logger
import org.slf4j.{LoggerFactory, Marker, MarkerFactory}


object Log4jControllerRegistration {
  private val logger = Logger(this.getClass.getName)

  try {
    val log4jController = Class.forName("kafka.utils.Log4jController").asInstanceOf[Class[Object]]
    val instance = log4jController.getDeclaredConstructor().newInstance()
    CoreUtils.registerMBean(instance, "kafka:type=kafka.Log4jController")
    logger.info("Registered kafka:type=kafka.Log4jController MBean")
  } catch {
    case _: Exception => logger.info("Couldn't register kafka:type=kafka.Log4jController MBean")
  }
}

private object Logging {
  private val FatalMarker: Marker = MarkerFactory.getMarker("FATAL")
}

trait Logging { // RequestChannel.scala의 RequestChannel 객체가 상속받는 트레잇

  protected lazy val logger: Logger = Logger(LoggerFactory.getLogger(loggerName)) // 로깅

  protected var logIdent: String = _ // 로그메시지 앞에 붙일 식별자

  Log4jControllerRegistration

  protected def loggerName: String = getClass.getName // 클래스 이름 추출

  protected def msgWithLogIdent(msg: String): String =
    if (logIdent == null) msg else logIdent + msg // 로그메시지 또는 식별자 + 로그메시지 만들기

  // 로그메시지: trace(디버깅), debug(디버깅), info(시스템 정보), warn(경고), error(오류), fatal(치명적 오류)
  def trace(msg: => String): Unit = logger.trace(msgWithLogIdent(msg))

  def trace(msg: => String, e: => Throwable): Unit = logger.trace(msgWithLogIdent(msg),e)

  // 디버깅 모드 확인 -> trace, debug
  def isDebugEnabled: Boolean = logger.underlying.isDebugEnabled

  def isTraceEnabled: Boolean = logger.underlying.isTraceEnabled

  def debug(msg: => String): Unit = logger.debug(msgWithLogIdent(msg))

  def debug(msg: => String, e: => Throwable): Unit = logger.debug(msgWithLogIdent(msg),e)

  def info(msg: => String): Unit = logger.info(msgWithLogIdent(msg))

  def info(msg: => String,e: => Throwable): Unit = logger.info(msgWithLogIdent(msg),e)

  def warn(msg: => String): Unit = logger.warn(msgWithLogIdent(msg))

  def warn(msg: => String, e: => Throwable): Unit = logger.warn(msgWithLogIdent(msg),e)

  def error(msg: => String): Unit = logger.error(msgWithLogIdent(msg))

  def error(msg: => String, e: => Throwable): Unit = logger.error(msgWithLogIdent(msg),e)

  def fatal(msg: => String): Unit =
    logger.error(Logging.FatalMarker, msgWithLogIdent(msg))

  def fatal(msg: => String, e: => Throwable): Unit =
    logger.error(Logging.FatalMarker, msgWithLogIdent(msg), e)
}
