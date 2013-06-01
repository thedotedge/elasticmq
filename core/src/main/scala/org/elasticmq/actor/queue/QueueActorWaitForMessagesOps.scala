package org.elasticmq.actor.queue

import org.elasticmq.msg.{ReceiveMessages, SendMessage, QueueMessageMsg}
import org.elasticmq.actor.reply._
import akka.actor.ActorRef
import scala.concurrent.{ duration => scd }
import org.joda.time.Duration
import scala.annotation.tailrec

trait QueueActorWaitForMessagesOps extends ReplyingActor with QueueActorMessageOps {
  this: QueueActorStorage =>

  private var senderSequence = 0L
  private val awaitingReply = new collection.mutable.HashMap[Long, AwaitingData]()

  override def receive = super.receive orElse {
    case ReplyIfTimeout(seq, replyWith) => {
      awaitingReply.remove(seq).foreach { case AwaitingData(originalSender, _, _) =>
        logger.debug(s"${queueData.name}: Awaiting messages: sequence $seq timed out. Replying with no messages.")
        originalSender ! replyWith
      }
    }
  }

  override def receiveAndReplyMessageMsg[T](msg: QueueMessageMsg[T]): ReplyAction[T] = msg match {
    case SendMessage(message) => {
      val result = super.receiveAndReplyMessageMsg(msg)
      tryReply()
      result
    }
    case rm@ReceiveMessages(visibilityTimeout, count, waitForMessagesOpt) => {
      val result = super.receiveAndReplyMessageMsg(msg)
      val waitForMessages = waitForMessagesOpt.getOrElse(queueData.receiveMessageWait)
      if (result == ReplyWith(Nil) && waitForMessages.getMillis > 0) {
        val seq = assignSequenceFor(rm)
        logger.debug(s"${queueData.name}: Awaiting messages: start for sequence $seq.")
        scheduleTimeoutReply(seq, waitForMessages)
        DoNotReply()
      } else result
    }
    case _ => super.receiveAndReplyMessageMsg(msg)
  }

  @tailrec
  private def tryReply() {
    awaitingReply.headOption match {
      case Some((seq, AwaitingData(originalSender, ReceiveMessages(visibilityTimeout, count, _), waitStart))) => {
        val received = super.receiveMessages(visibilityTimeout, count)

        if (received != Nil) {
          originalSender ! received
          logger.debug(s"${queueData.name}: Awaiting messages: replying to sequence $seq with ${received.size} messages.")
          awaitingReply.remove(seq)

          tryReply()
        }
      }
      case _ => // do nothing
    }
  }

  private def assignSequenceFor(receiveMessages: ReceiveMessages): Long = {
    val seq = senderSequence
    senderSequence += 1
    awaitingReply(seq) = AwaitingData(sender, receiveMessages, nowProvider.nowMillis)
    seq
  }

  private def scheduleTimeoutReply(seq: Long, waitForMessages: Duration) {
    import context.dispatcher
    context.system.scheduler.scheduleOnce(
      scd.Duration(waitForMessages.getMillis, scd.MILLISECONDS),
      self,
      ReplyIfTimeout(seq, Nil))
  }

  case class ReplyIfTimeout(seq: Long, replyWith: AnyRef)

  case class AwaitingData(originalSender: ActorRef, originalReceiveMessages: ReceiveMessages, waitStart: Long)
}
