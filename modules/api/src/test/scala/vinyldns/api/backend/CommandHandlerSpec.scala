/*
 * Copyright 2018 Comcast Cable Communications Management, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package vinyldns.api.backend
import java.util.concurrent.Executors

import cats.effect.IO
import cats.scalatest.EitherMatchers
import fs2._
import org.mockito
import org.mockito.Matchers._
import org.mockito.{ArgumentCaptor, Mockito}
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterEach, EitherValues, Matchers, WordSpec}
import vinyldns.api.VinylDNSTestData
import vinyldns.api.backend.CommandHandler.{DeleteMessage, RetryMessage}
import vinyldns.api.domain.dns.DnsConnection
import vinyldns.core.domain.batch.BatchChangeRepository
import vinyldns.core.domain.record.{RecordChangeRepository, RecordSetChange, RecordSetRepository}
import vinyldns.core.domain.zone._
import vinyldns.core.queue.{CommandMessage, MessageCount, MessageHandle, MessageQueue}

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

class CommandHandlerSpec
    extends WordSpec
    with Matchers
    with MockitoSugar
    with BeforeAndAfterEach
    with VinylDNSTestData
    with EitherValues
    with EitherMatchers {

  private case class TestHandle(value: String) extends MessageHandle
  private val mq = mock[MessageQueue]
  implicit val sched: Scheduler =
    Scheduler.fromScheduledExecutorService(Executors.newScheduledThreadPool(2))
  private val messages = for { i <- 0 to 10 } yield
    CommandMessage(TestHandle(i.toString), pendingCreateAAAA)
  private val count = MessageCount(10).right.value

  private val mockZoneChangeProcessor = mock[ZoneChange => IO[ZoneChange]]
  private val mockRecordChangeProcessor =
    mock[(DnsConnection, RecordSetChange) => IO[RecordSetChange]]
  private val mockZoneSyncProcessor = mock[ZoneChange => IO[ZoneChange]]
  private val defaultConn =
    ZoneConnection("vinyldns.", "vinyldns.", "nzisn+4G2ldMn0q1CV3vsg==", "10.1.1.1")
  private val processor =
    CommandHandler.processChangeRequests(
      mockZoneChangeProcessor,
      mockRecordChangeProcessor,
      mockZoneSyncProcessor,
      defaultConn
    )

  override protected def beforeEach(): Unit =
    Mockito.reset(mq, mockZoneChangeProcessor, mockRecordChangeProcessor, mockZoneSyncProcessor)

  "polling" should {
    "poll for messages" in {
      doReturn(IO.pure(messages.toList)).when(mq).receive(count)
      val run = CommandHandler.startPolling(mq, count, 50.millis).take(1)
      val result = run.compile.toVector.unsafeRunSync()
      val read = result.head.compile.toList.unsafeRunSync()
      read should contain theSameElementsAs messages
    }
    "continue polling for messages on failure" in {
      doReturn(IO.raiseError(new RuntimeException("fail")))
        .doReturn(IO.raiseError(new RuntimeException("fail")))
        .doReturn(IO.pure(messages.toList))
        .when(mq)
        .receive(count)

      val run = CommandHandler.startPolling(mq, count, 10.millis).take(1)
      val result = run.compile.toVector.unsafeRunSync()
      val read = result.head.compile.toList.unsafeRunSync()
      read should contain theSameElementsAs messages
    }
  }
  "change message visibility" should {
    "update the timeout for zone syncs" in {
      doReturn(IO.unit).when(mq).changeMessageTimeout(any[CommandMessage], any[FiniteDuration])
      val sync = zoneCreate.copy(changeType = ZoneChangeType.Sync)
      val msg = CommandMessage(TestHandle("foo"), sync)
      Stream
        .emit(msg)
        .covary[IO]
        .to(CommandHandler.changeVisibilityTimeoutWhenSyncing(mq))
        .compile
        .drain
        .unsafeRunSync()

      verify(mq).changeMessageTimeout(msg, 1200.seconds)
    }
    "update the timeout for zone creates" in {
      doReturn(IO.unit).when(mq).changeMessageTimeout(any[CommandMessage], any[FiniteDuration])
      val msg = CommandMessage(TestHandle("foo"), zoneCreate)
      Stream
        .emit(msg)
        .covary[IO]
        .to(CommandHandler.changeVisibilityTimeoutWhenSyncing(mq))
        .compile
        .drain
        .unsafeRunSync()

      verify(mq).changeMessageTimeout(msg, 1200.seconds)
    }
    "not update the timeout for zone deletes" in {
      val del = zoneCreate.copy(changeType = ZoneChangeType.Delete)
      val msg = CommandMessage(TestHandle("foo"), del)
      Stream
        .emit(msg)
        .covary[IO]
        .to(CommandHandler.changeVisibilityTimeoutWhenSyncing(mq))
        .compile
        .drain
        .unsafeRunSync()

      verifyZeroInteractions(mq)
    }
    "not update the timeout for record changes" in {
      val msg = CommandMessage(TestHandle("foo"), pendingCreateAAAA)
      Stream
        .emit(msg)
        .covary[IO]
        .to(CommandHandler.changeVisibilityTimeoutWhenSyncing(mq))
        .compile
        .drain
        .unsafeRunSync()

      verifyZeroInteractions(mq)
    }
  }

  "determining outcome" should {
    "generate a retry for failures" in {
      val msg = CommandMessage(TestHandle("foo"), pendingCreateAAAA)
      val result =
        CommandHandler.outcomeOf(msg)(IO.raiseError(new RuntimeException("fail"))).unsafeRunSync()
      result shouldBe RetryMessage(msg)
    }
    "generate delete for successes" in {
      val msg = CommandMessage(TestHandle("foo"), pendingCreateAAAA)
      val result = CommandHandler.outcomeOf(msg)(IO.unit).unsafeRunSync()
      result shouldBe DeleteMessage(msg)
    }
  }

  "message sink" should {
    "retry messages" in {
      val msg = RetryMessage(CommandMessage(TestHandle("foo"), pendingCreateAAAA))
      doReturn(IO.unit).when(mq).requeue(msg.message)
      Stream.emit(msg).covary[IO].to(CommandHandler.messageSink(mq)).compile.drain.unsafeRunSync()
      verify(mq).requeue(msg.message)
    }
    "remove messages" in {
      val msg = DeleteMessage(CommandMessage(TestHandle("foo"), pendingCreateAAAA))
      doReturn(IO.unit).when(mq).remove(msg.message)
      Stream.emit(msg).covary[IO].to(CommandHandler.messageSink(mq)).compile.drain.unsafeRunSync()
      verify(mq).remove(msg.message)
    }
  }

  "processing change requests" should {
    "handle record changes" in {
      val change = CommandMessage(TestHandle("foo"), pendingCreateAAAA)
      doReturn(IO.pure(change))
        .when(mockRecordChangeProcessor)
        .apply(any[DnsConnection], any[RecordSetChange])
      Stream.emit(change).covary[IO].through(processor).compile.drain.unsafeRunSync()
      verify(mockRecordChangeProcessor).apply(any[DnsConnection], any[RecordSetChange])
      verifyZeroInteractions(mockZoneSyncProcessor)
      verifyZeroInteractions(mockZoneChangeProcessor)
    }
    "use the default zone connection when the change zone connection is not defined" in {
      val noConnChange =
        pendingCreateAAAA.copy(
          zone = pendingCreateAAAA.zone.copy(connection = None, transferConnection = None))
      val default = defaultConn.copy(primaryServer = "default.conn.test.com")
      val defaultConnProcessor =
        CommandHandler.processChangeRequests(
          mockZoneChangeProcessor,
          mockRecordChangeProcessor,
          mockZoneSyncProcessor,
          default
        )
      val change = CommandMessage(TestHandle("foo"), noConnChange)
      doReturn(IO.pure(change))
        .when(mockRecordChangeProcessor)
        .apply(any[DnsConnection], any[RecordSetChange])
      Stream.emit(change).covary[IO].through(defaultConnProcessor).compile.drain.unsafeRunSync()

      val connCaptor = ArgumentCaptor.forClass(classOf[DnsConnection])
      verify(mockRecordChangeProcessor).apply(connCaptor.capture(), any[RecordSetChange])
      val resolver = connCaptor.getValue.resolver
      resolver.getAddress.getHostName shouldBe default.primaryServer
    }
    "handle zone creates" in {
      val change = CommandMessage(TestHandle("foo"), zoneCreate)
      doReturn(IO.pure(zoneCreate))
        .doReturn(IO.pure(change))
        .when(mockZoneChangeProcessor)
        .apply(zoneCreate)
      doReturn(IO.pure(zoneCreate)).when(mockZoneSyncProcessor).apply(zoneCreate)
      Stream.emit(change).covary[IO].through(processor).compile.drain.unsafeRunSync()
      verify(mockZoneChangeProcessor, times(2)).apply(zoneCreate)
      verify(mockZoneSyncProcessor).apply(zoneCreate)
      verifyZeroInteractions(mockRecordChangeProcessor)
    }
    "handle zone syncs" in {
      val sync = zoneCreate.copy(changeType = ZoneChangeType.Sync)
      val change = CommandMessage(TestHandle("foo"), sync)
      doReturn(IO.pure(sync)).doReturn(IO.pure(change)).when(mockZoneChangeProcessor).apply(sync)
      doReturn(IO.pure(sync)).when(mockZoneSyncProcessor).apply(sync)
      Stream.emit(change).covary[IO].through(processor).compile.drain.unsafeRunSync()
      verify(mockZoneChangeProcessor, times(2)).apply(sync)
      verify(mockZoneSyncProcessor).apply(sync)
      verifyZeroInteractions(mockRecordChangeProcessor)
    }
    "handle zone deletes" in {
      val del = zoneCreate.copy(changeType = ZoneChangeType.Delete)
      val change = CommandMessage(TestHandle("foo"), del)
      doReturn(IO.pure(del)).doReturn(IO.pure(change)).when(mockZoneChangeProcessor).apply(del)
      Stream.emit(change).covary[IO].through(processor).compile.drain.unsafeRunSync()
      verify(mockZoneChangeProcessor).apply(del)
      verifyZeroInteractions(mockZoneSyncProcessor)
      verifyZeroInteractions(mockRecordChangeProcessor)
    }
  }

  "main flow" should {
    "process successfully" in {
      val stop = fs2.async.signalOf[IO, Boolean](false).unsafeRunSync()
      val cmd = CommandMessage(TestHandle("foo"), pendingCreateAAAA)

      // stage pulling from the message queue
      doReturn(IO.pure(List(cmd))).when(mq).receive(count)

      // stage our record change processing
      doReturn(IO.pure(cmd))
        .when(mockRecordChangeProcessor)
        .apply(any[DnsConnection], any[RecordSetChange])

      // stage removing from the queue
      doReturn(IO.unit).when(mq).remove(cmd)

      val flow =
        CommandHandler
          .mainFlow(
            mockZoneChangeProcessor,
            mockRecordChangeProcessor,
            mockZoneSyncProcessor,
            mq,
            count,
            100.millis,
            stop,
            defaultConn
          )
          .take(1)

      // kick off processing of messages
      flow.compile.drain.unsafeRunSync()

      // verify our interactions
      verify(mq, atLeastOnce()).receive(count)
      verify(mockRecordChangeProcessor)
        .apply(any[DnsConnection], mockito.Matchers.eq(pendingCreateAAAA))
      verify(mq).remove(cmd)
    }
    "continue processing on unexpected failure" in {
      val stop = fs2.async.signalOf[IO, Boolean](false).unsafeRunSync()
      val cmd = CommandMessage(TestHandle("foo"), pendingCreateAAAA)

      // stage pulling from the message queue, make sure we always return our command
      doReturn(IO.pure(List(cmd)))
        .doReturn(IO.pure(List(cmd)))
        .when(mq)
        .receive(count)

      // stage our record change processing failure, and then a success
      doReturn(IO.raiseError(new RuntimeException("fail")))
        .doReturn(IO.pure(cmd.command))
        .when(mockRecordChangeProcessor)
        .apply(any[DnsConnection], any[RecordSetChange])

      // stage removing from the queue
      doReturn(IO.unit).when(mq).remove(cmd)

      val flow =
        CommandHandler
          .mainFlow(
            mockZoneChangeProcessor,
            mockRecordChangeProcessor,
            mockZoneSyncProcessor,
            mq,
            count,
            100.millis,
            stop,
            defaultConn
          )
          .take(1)

      // kick off processing of messages
      flow.compile.drain.unsafeRunSync()

      // verify our interactions
      verify(mq, atLeastOnce()).receive(count)

      // verify that our record was attempted two times
      verify(mockRecordChangeProcessor, times(2))
        .apply(any[DnsConnection], mockito.Matchers.eq(pendingCreateAAAA))
      verify(mq).remove(cmd)
    }
  }

  "run" should {
    "process a zone update change through the flow" in {
      // testing the run method, which does nothing more than simplify construction of the main flow
      val stop = fs2.async.signalOf[IO, Boolean](false).unsafeRunSync()
      val cmd = CommandMessage(TestHandle("foo"), zoneUpdate)

      val zoneRepo = mock[ZoneRepository]
      val zoneChangeRepo = mock[ZoneChangeRepository]
      val recordSetRepo = mock[RecordSetRepository]
      val recordChangeRepo = mock[RecordChangeRepository]
      val batchChangeRepo = mock[BatchChangeRepository]

      // stage pulling from the message queue, return the message in the first poll, stage a few additional
      // polls just in case
      doReturn(IO.pure(List(cmd)))
        .doReturn(IO.pure(List()))
        .doReturn(IO.pure(List()))
        .doReturn(IO.pure(List()))
        .when(mq)
        .receive(count)

      // stage processing for a zone update, the simplest of cases
      doReturn(IO.pure(zoneUpdate.zone)).when(zoneRepo).save(zoneUpdate.zone)
      doReturn(IO.pure(zoneUpdate)).when(zoneChangeRepo).save(any[ZoneChange])

      // stage removing from the queue
      doReturn(IO.unit).when(mq).remove(cmd)

      val flow =
        CommandHandler
          .run(
            mq,
            count,
            stop,
            100.millis,
            zoneRepo,
            zoneChangeRepo,
            recordSetRepo,
            recordChangeRepo,
            batchChangeRepo,
            defaultConn
          )

      // kick off processing of messages
      flow.unsafeRunAsync { _ =>
        ()
      }

      // sleep to allow the flow to run once
      Thread.sleep(200)

      // shutoff processing on the flow
      stop.set(true).unsafeRunSync()

      // verify our interactions
      verify(mq, atLeastOnce()).receive(count)
      verify(zoneRepo).save(zoneUpdate.zone)
      verify(zoneChangeRepo).save(any[ZoneChange])
      verify(mq).remove(cmd)
    }
  }
}
