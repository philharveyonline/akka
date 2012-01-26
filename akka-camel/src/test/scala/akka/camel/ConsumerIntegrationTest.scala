/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel

import akka.actor._
import org.scalatest.matchers.MustMatchers
import org.scalatest.mock.MockitoSugar
import org.mockito.Matchers.{ eq ⇒ the }
import akka.util.duration._
import java.util.concurrent.TimeUnit._
import org.apache.camel.{ FailedToCreateRouteException, CamelExecutionException }
import TestSupport._
import java.util.concurrent.{ TimeoutException, CountDownLatch }
import org.scalatest.{ WordSpec, BeforeAndAfterEach }
import org.apache.camel.model.RouteDefinition
import org.apache.camel.builder.Builder

class ConsumerIntegrationTest extends WordSpec with MustMatchers with MockitoSugar with BeforeAndAfterEach {
  implicit var system: ActorSystem = _

  override protected def beforeEach() {
    system = ActorSystem("test")
  }

  override protected def afterEach() {
    system.shutdown()
  }


  //TODO test manualAck

  //TODO: decide on Camel lifecycle. Ideally it must prevent creating non-started instances, so there is no need to test if consumers fail when Camel is not initialized.
  "Consumer must fail if camel is not started" in (pending)

  "Consumer must throw FailedToCreateRouteException, while awaiting activation, if endpoint is invalid" in {
    val actorRef = system.actorOf(Props(new TestActor(uri = "some invalid uri")))

    intercept[FailedToCreateRouteException] {
      CamelExtension(system).awaitActivation(actorRef, 1 second)
    }
  }

  def camel: Camel = {
    CamelExtension(system)
  }

  "Consumer must support in-out messaging" in {
    start(new Consumer {
      def endpointUri = "direct:a1"
      protected def receive = {
        case m: Message ⇒ sender ! "received " + m.bodyAs[String]
      }
    })
    camel.sendTo("direct:a1", msg = "some message") must be("received some message")
  }

  "Consumer must support blocking, in-out messaging" in {
    start(new Consumer {
      def endpointUri = "direct:a1"
      override def blocking = true
      override def replyTimeout = 200 millis

      protected def receive = {
        case m: Message ⇒ {
          Thread.sleep(150)
          sender ! "received " + m.bodyAs[String]
        }
      }
    })
    time(camel.sendTo("direct:a1", msg = "some message")) must be >= (150 millis)
  }

  "Consumer must time-out if consumer is slow" in {
    val SHORT_TIMEOUT = 10 millis
    val LONG_WAIT = 200 millis

    start(new Consumer {
      override def replyTimeout = SHORT_TIMEOUT

      def endpointUri = "direct:a3"
      protected def receive = { case _ ⇒ { Thread.sleep(LONG_WAIT.toMillis); sender ! "done" } }
    })

    val exception = intercept[CamelExecutionException] {
      camel.sendTo("direct:a3", msg = "some msg 3")
    }
    exception.getCause.getClass must be(classOf[TimeoutException])
  }

  "Consumer must process messages even after actor restart" in {
    val restarted = new CountDownLatch(1)
    val consumer = start(new Consumer {
      def endpointUri = "direct:a2"

      protected def receive = {
        case "throw"    ⇒ throw new Exception
        case m: Message ⇒ sender ! "received " + m.bodyAs[String]
      }

      override def postRestart(reason: Throwable) {
        restarted.countDown()
      }
    })
    consumer ! "throw"
    if (!restarted.await(1, SECONDS)) fail("Actor failed to restart!")

    val response = camel.sendTo("direct:a2", msg = "xyz")
    response must be("received xyz")
  }

  "Consumer must unregister itself when stopped" in {
    val consumer = start(new TestActor())
    camel.awaitActivation(consumer, 1 second)

    camel.routeCount must be > (0)

    system.stop(consumer)
    camel.awaitDeactivation(consumer, 1 second)

    camel.routeCount must be(0)
  }

  "Error passing consumer supports error handling through route modification" in {
    start(new ErrorThrowingConsumer("direct:error-handler-test") with ErrorPassing{
      override def onRouteDefinition(rd: RouteDefinition) = {
        rd.onException(classOf[Exception]).handled(true).transform(Builder.exceptionMessage).end
      }
    })
    camel.sendTo("direct:error-handler-test", msg = "hello") must be("error: hello")
  }

  "Error passing consumer supports redelivery through route modification" in {
    start(new FailingOnceConsumer("direct:failing-once-concumer") with ErrorPassing{
      override def onRouteDefinition(rd: RouteDefinition) = {
        rd.onException(classOf[Exception]).maximumRedeliveries(1).end
      }
    })
    camel.sendTo("direct:failing-once-concumer", msg = "hello") must be("accepted: hello")
  }

}

class ErrorThrowingConsumer(override val endpointUri :String) extends Consumer{
  def receive = {
    case msg: Message => throw new Exception("error: %s" format msg.body)
  }

}

class FailingOnceConsumer(override val endpointUri :String) extends Consumer{

  def receive = {
    case msg: Message =>
      if (msg.containsHeader("CamelRedelivered") && msg.headerAs[Boolean]("CamelRedelivered"))
        sender ! ("accepted: %s" format msg.body)
      else
        throw new Exception("rejected: %s" format msg.body)
  }
}



class TestActor(uri: String = "file://target/abcde") extends Actor with Consumer {
  def endpointUri = uri
  protected def receive = { case _ ⇒ println("foooo..") }
}
