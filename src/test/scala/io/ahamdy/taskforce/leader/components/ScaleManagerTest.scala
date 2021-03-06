package io.ahamdy.taskforce.leader.components

import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicInteger

import monix.eval.Task
import io.ahamdy.taskforce.api.{DummyCloudManager, DummyNodeInfoProvider}
import io.ahamdy.taskforce.common.DummyTime
import io.ahamdy.taskforce.domain._
import io.ahamdy.taskforce.store.DummyNodeStore
import io.ahamdy.taskforce.testing.StandardSpec
import io.ahamdy.taskforce.syntax.zonedDateTime._

import scala.concurrent.duration._

class ScaleManagerTest extends StandardSpec {
  val config = ScaleManagerConfig(
    minNodes = 1,
    maxNodes = 5,
    coolDownPeriod = 1.minute,
    scaleDownThreshold = 30,
    scaleUpThreshold = 80,
    evaluationPeriod = 1.minute,
    scaleUpStep = 2,
    scaleDownStep = 2)

  val nodeInfoProvider = new DummyNodeInfoProvider("node-1", "test-group")

  "ScaleManagerTest" should {
    "scaleUpIfDue" should {
      "mark scaleUpNeededSince by now if it was None and do nothing to cluster" in {
        val time = new DummyTime(ZonedDateTime.now())
        val cloudManager = new DummyCloudManager(initialNodesCount = 2)
        val nodeStore = new DummyNodeStore(time, nodeInfoProvider.nodeGroup)
        val scaleManager = new ScaleManagerImpl(config, cloudManager, nodeInfoProvider, nodeStore, time)

        scaleManager.scaleUpNeededSince.get must beNone
        cloudManager.nodesCounter.get mustEqual 2

        scaleManager.scaleUpIfDue(time.unsafeNow()) must beSucceedingIO

        scaleManager.scaleUpNeededSince.get must beSome(time.unsafeNow())
        cloudManager.nodesCounter.get mustEqual 2
      }

      "do nothing if evaluationPeriod has not been exceeded yet since scaleUpNeededSince" in {
        val time = new DummyTime(ZonedDateTime.now())
        val cloudManager = new DummyCloudManager(initialNodesCount = 2)
        val nodeStore = new DummyNodeStore(time, nodeInfoProvider.nodeGroup)
        val scaleManager = new ScaleManagerImpl(config, cloudManager, nodeInfoProvider, nodeStore, time)

        scaleManager.scaleUpIfDue(time.unsafeNow()) must beSucceedingIO

        val scaleUpNeededSince = time.unsafeNow()
        scaleManager.scaleUpNeededSince.get must beSome(scaleUpNeededSince)
        scaleManager.lastScaleActivity.get mustEqual time.epoch
        cloudManager.nodesCounter.get mustEqual 2

        time.currentTime.set(time.unsafeNow().plus(config.evaluationPeriod).minus(1.second))

        scaleManager.scaleUpIfDue(time.unsafeNow()) must beSucceedingIO

        scaleManager.scaleUpNeededSince.get must beSome(scaleUpNeededSince)
        scaleManager.lastScaleActivity.get mustEqual time.epoch
        cloudManager.nodesCounter.get mustEqual 2
      }

      "do nothing if maxNodes has been reached" in {
        val time = new DummyTime(ZonedDateTime.now())
        val cloudManager = new DummyCloudManager(initialNodesCount = 2)
        val nodeStore = new DummyNodeStore(time, nodeInfoProvider.nodeGroup)
        val scaleManager = new ScaleManagerImpl(config.copy(maxNodes = 2), cloudManager, nodeInfoProvider, nodeStore, time)

        scaleManager.scaleUpIfDue(time.unsafeNow()) must beSucceedingIO

        val scaleUpNeededSince = time.unsafeNow()
        scaleManager.scaleUpNeededSince.get must beSome(scaleUpNeededSince)
        scaleManager.lastScaleActivity.get mustEqual time.epoch
        cloudManager.nodesCounter.get mustEqual 2

        time.currentTime.set(time.unsafeNow().plus(config.evaluationPeriod))

        scaleManager.scaleUpIfDue(time.unsafeNow()) must beSucceedingIO

        scaleManager.scaleUpNeededSince.get must beSome(scaleUpNeededSince)
        scaleManager.lastScaleActivity.get mustEqual time.epoch
        cloudManager.nodesCounter.get mustEqual 2
      }

      "scale up cluster only if evaluationPeriod has been exceeded since scaleUpNeededSince and nodes < maxNodes using scaleUpStep" in {
        val time = new DummyTime(ZonedDateTime.now())
        val cloudManager = new DummyCloudManager(initialNodesCount = 2)
        val nodeStore = new DummyNodeStore(time, nodeInfoProvider.nodeGroup)
        val scaleManager = new ScaleManagerImpl(config, cloudManager, nodeInfoProvider, nodeStore, time)

        scaleManager.scaleUpIfDue(time.unsafeNow()) must beSucceedingIO

        time.currentTime.set(time.unsafeNow().plus(config.evaluationPeriod))

        scaleManager.scaleUpIfDue(time.unsafeNow()) must beSucceedingIO

        scaleManager.scaleUpNeededSince.get must beNone
        scaleManager.lastScaleActivity.get mustEqual time.unsafeNow()
        cloudManager.nodesCounter.get mustEqual (2 + config.scaleUpStep)
      }

      "scale up cluster using remaining cluster capacity if scaleUpStep > remaining capacity" in {
        val time = new DummyTime(ZonedDateTime.now())
        val cloudManager = new DummyCloudManager(initialNodesCount = 2)
        val nodeStore = new DummyNodeStore(time, nodeInfoProvider.nodeGroup)
        val scaleConfig = config.copy(maxNodes = 5, scaleUpStep = 4)
        val scaleManager = new ScaleManagerImpl(scaleConfig, cloudManager, nodeInfoProvider, nodeStore, time)

        scaleManager.scaleUpIfDue(time.unsafeNow()) must beSucceedingIO
        time.currentTime.set(time.unsafeNow().plus(config.evaluationPeriod))
        scaleManager.scaleUpIfDue(time.unsafeNow()) must beSucceedingIO

        cloudManager.nodesCounter.get mustEqual scaleConfig.maxNodes
      }
    }

    "scaleDownIfDue" should {
      "mark scaleDownNeededSince by now if it was None and do nothing to cluster" in {
        val time = new DummyTime(ZonedDateTime.now())
        val cloudManager = new DummyCloudManager(initialNodesCount = 2)
        val nodeStore = new DummyNodeStore(time, nodeInfoProvider.nodeGroup)
        val scaleManager = new ScaleManagerImpl(config, cloudManager, nodeInfoProvider, nodeStore, time)

        scaleManager.scaleDownNeededSince.get must beNone
        nodeStore.getAllActiveNodesCountByGroup(nodeInfoProvider.nodeGroup) must beSucceedingIO(2)

        scaleManager.scaleDownIfDue(time.unsafeNow()) must beSucceedingIO

        scaleManager.scaleDownNeededSince.get must beSome(time.unsafeNow())
        nodeStore.getAllActiveNodesCountByGroup(nodeInfoProvider.nodeGroup) must beSucceedingIO(2)
        scaleManager.lastScaleActivity.get mustEqual time.epoch
      }

      "do nothing if evaluationPeriod has not been exceeded yet since scaleDownNeededSince" in {
        val time = new DummyTime(ZonedDateTime.now())
        val cloudManager = new DummyCloudManager(initialNodesCount = 2)
        val nodeStore = new DummyNodeStore(time, nodeInfoProvider.nodeGroup)
        val scaleManager = new ScaleManagerImpl(config, cloudManager, nodeInfoProvider, nodeStore, time)
        val scaleDownIfDue = time.unsafeNow()

        scaleManager.scaleDownIfDue(scaleDownIfDue) must beSucceedingIO

        scaleManager.scaleDownNeededSince.get must beSome(scaleDownIfDue)
        nodeStore.getAllActiveNodesCountByGroup(nodeInfoProvider.nodeGroup) must beSucceedingIO(2)

        time.currentTime.set(time.unsafeNow().plus(config.evaluationPeriod).minus(1.second))

        scaleManager.scaleDownIfDue(scaleDownIfDue) must beSucceedingIO

        scaleManager.scaleDownNeededSince.get must beSome(scaleDownIfDue)
        nodeStore.getAllActiveNodesCountByGroup(nodeInfoProvider.nodeGroup) must beSucceedingIO(2)
      }

      "do nothing if minNodes has been reached" in {
        val time = new DummyTime(ZonedDateTime.now())
        val cloudManager = new DummyCloudManager(initialNodesCount = 2)
        val nodeStore = new DummyNodeStore(time, nodeInfoProvider.nodeGroup)
        val scaleManager = new ScaleManagerImpl(config.copy(minNodes = 2), cloudManager, nodeInfoProvider, nodeStore, time)

        scaleManager.scaleDownIfDue(time.unsafeNow()) must beSucceedingIO

        val scaleDownNeededSince = time.unsafeNow()
        scaleManager.scaleDownNeededSince.get must beSome(scaleDownNeededSince)
        scaleManager.lastScaleActivity.get mustEqual time.epoch
        nodeStore.getAllActiveNodesCountByGroup(nodeInfoProvider.nodeGroup) must beSucceedingIO(2)

        time.currentTime.set(time.unsafeNow().plus(config.evaluationPeriod))

        scaleManager.scaleDownIfDue(time.unsafeNow()) must beSucceedingIO

        scaleManager.scaleDownNeededSince.get must beSome(scaleDownNeededSince)
        scaleManager.lastScaleActivity.get mustEqual time.epoch
        nodeStore.getAllActiveNodesCountByGroup(nodeInfoProvider.nodeGroup) must beSucceedingIO(2)
      }

      "scale down using scaleDownStep only if evaluationPeriod has been exceeded since scaleUpNeededSince and nodes > minNodes" in {
        val time = new DummyTime(ZonedDateTime.now())
        val cloudManager = new DummyCloudManager(initialNodesCount = 3)
        val nodeStore = new DummyNodeStore(time, nodeInfoProvider.nodeGroup)
        nodeStore.nodesList.set(List(
          JobNode(NodeId("test-node-1"), NodeGroup("test-group"), time.unsafeNow().minusMinutes(1), NodeActive(true), NodeVersion("1.0.0")),
          JobNode(NodeId("test-node-2"), NodeGroup("test-group"), time.unsafeNow(), NodeActive(true), NodeVersion("1.0.0")),
          JobNode(NodeId("test-node-3"), NodeGroup("test-group"), time.unsafeNow().minusMinutes(1), NodeActive(true), NodeVersion("1.0.0"))))
        val scaleManager = new ScaleManagerImpl(config, cloudManager, nodeInfoProvider, nodeStore, time)

        nodeStore.getAllActiveNodesCountByGroup(nodeInfoProvider.nodeGroup) must beSucceedingIO(3)

        scaleManager.scaleDownIfDue(time.unsafeNow()) must beSucceedingIO

        time.currentTime.set(time.unsafeNow().plus(config.evaluationPeriod))

        scaleManager.scaleDownIfDue(time.unsafeNow()) must beSucceedingIO

        scaleManager.scaleDownNeededSince.get must beNone
        scaleManager.lastScaleActivity.get mustEqual time.unsafeNow()
        nodeStore.getAllActiveNodesCountByGroup(nodeInfoProvider.nodeGroup) must beSucceedingIO(3 - config.scaleDownStep)
      }

      "scale down cluster using remaining cluster capacity if scaleDownStep > remaining number to reach minNodes" in {
        val time = new DummyTime(ZonedDateTime.now())
        val cloudManager = new DummyCloudManager(initialNodesCount = 3)
        val nodeStore = new DummyNodeStore(time, nodeInfoProvider.nodeGroup)
        nodeStore.nodesList.set(List(
          JobNode(NodeId("test-node-1"), NodeGroup("test-group"), time.unsafeNow().minusMinutes(1), NodeActive(true), NodeVersion("1.0.0")),
          JobNode(NodeId("test-node-2"), NodeGroup("test-group"), time.unsafeNow(), NodeActive(true), NodeVersion("1.0.0")),
          JobNode(NodeId("test-node-3"), NodeGroup("test-group"), time.unsafeNow().minusMinutes(1), NodeActive(true), NodeVersion("1.0.0"))))
        val scaleConfig = config.copy(minNodes = 2, scaleDownStep = 4)
        val scaleManager = new ScaleManagerImpl(scaleConfig, cloudManager, nodeInfoProvider, nodeStore, time)

        nodeStore.getAllActiveNodesCountByGroup(nodeInfoProvider.nodeGroup) must beSucceedingIO(3)

        scaleManager.scaleDownIfDue(time.unsafeNow()) must beSucceedingIO
        time.currentTime.set(time.unsafeNow().plus(config.evaluationPeriod))
        scaleManager.scaleDownIfDue(time.unsafeNow()) must beSucceedingIO

        nodeStore.getAllActiveNodesCountByGroup(nodeInfoProvider.nodeGroup) must beSucceedingIO(scaleConfig.minNodes)
      }
    }

    "scaleCluster" should {
      "set scaleDownNeededSince to None and call scaleUpIfDue only if coolDownPeriod has passed since lastScaleActivity" +
        " and current queued and running jobs exceeded configured scaleUpThreshold of current active nodes capacity" in {
        val time = new DummyTime(ZonedDateTime.now())
        val cloudManager = new DummyCloudManager(initialNodesCount = 3)
        val nodeStore = new DummyNodeStore(time, nodeInfoProvider.nodeGroup)
        val scaleManager = new ScaleManagerImpl(config, cloudManager, nodeInfoProvider, nodeStore, time) {
          val scaleUpCounter = new AtomicInteger(0)
          val scaleDownCounter = new AtomicInteger(0)
          override def scaleUpIfDue(now: ZonedDateTime): Task[Unit] = Task(scaleUpCounter.incrementAndGet())
          override def scaleDownIfDue(now: ZonedDateTime): Task[Unit] = Task(scaleDownCounter.incrementAndGet())
        }

        scaleManager.lastScaleActivity.set(time.unsafeNow())
        scaleManager.scaleCluster(queuedAndRunningWeights = 90, activeNodesCapacity = 100) must beSucceedingIO // more than 80%

        scaleManager.scaleUpCounter.get mustEqual 0
        scaleManager.scaleDownCounter.get mustEqual 0

        scaleManager.scaleDownNeededSince.set(Some(time.unsafeNow()))
        scaleManager.lastScaleActivity.set(time.unsafeNow().minus(config.coolDownPeriod))
        scaleManager.scaleCluster(queuedAndRunningWeights = 90, activeNodesCapacity = 100) must beSucceedingIO // more than 80%

        scaleManager.scaleUpCounter.get mustEqual 1
        scaleManager.scaleDownCounter.get mustEqual 0
        scaleManager.scaleDownNeededSince.get must beNone
      }

      "set scaleUpNeededSince to None and call scaleDownIfDue only if coolDownPeriod has passed since lastScaleActivity" +
        " and current queued and running jobs are less than configured scaleDownThreshold of current active nodes capacity" in {
        val time = new DummyTime(ZonedDateTime.now())
        val cloudManager = new DummyCloudManager(initialNodesCount = 3)
        val nodeStore = new DummyNodeStore(time, nodeInfoProvider.nodeGroup)
        val scaleManager = new ScaleManagerImpl(config, cloudManager, nodeInfoProvider, nodeStore, time) {
          val scaleUpCounter = new AtomicInteger(0)
          val scaleDownCounter = new AtomicInteger(0)
          override def scaleUpIfDue(now: ZonedDateTime): Task[Unit] = Task(scaleUpCounter.incrementAndGet())
          override def scaleDownIfDue(now: ZonedDateTime): Task[Unit] = Task(scaleDownCounter.incrementAndGet())
        }

        scaleManager.lastScaleActivity.set(time.unsafeNow())
        scaleManager.scaleCluster(queuedAndRunningWeights = 10, activeNodesCapacity = 100) must beSucceedingIO // less than 30%

        scaleManager.scaleUpCounter.get mustEqual 0
        scaleManager.scaleDownCounter.get mustEqual 0

        scaleManager.scaleUpNeededSince.set(Some(time.unsafeNow()))
        scaleManager.lastScaleActivity.set(time.unsafeNow().minus(config.coolDownPeriod))
        scaleManager.scaleCluster(queuedAndRunningWeights = 10, activeNodesCapacity = 100) must beSucceedingIO // less than 30%

        scaleManager.scaleUpCounter.get mustEqual 0
        scaleManager.scaleDownCounter.get mustEqual 1
        scaleManager.scaleUpNeededSince.get must beNone
      }

      "set scaleDownNeededSince and scaleUpNeededSince to None if current queued and running jobs are in between " +
        "configured scaleDownThreshold and scaleIpThreshold of current active nodes capacity" in {
        val time = new DummyTime(ZonedDateTime.now())
        val cloudManager = new DummyCloudManager(initialNodesCount = 3)
        val nodeStore = new DummyNodeStore(time, nodeInfoProvider.nodeGroup)
        val scaleManager = new ScaleManagerImpl(config, cloudManager, nodeInfoProvider, nodeStore, time) {
          val scaleUpCounter = new AtomicInteger(0)
          val scaleDownCounter = new AtomicInteger(0)
          override def scaleUpIfDue(now: ZonedDateTime): Task[Unit] = Task(scaleUpCounter.incrementAndGet())
          override def scaleDownIfDue(now: ZonedDateTime): Task[Unit] = Task(scaleDownCounter.incrementAndGet())
        }

        scaleManager.scaleUpNeededSince.set(Some(time.unsafeNow()))
        scaleManager.scaleDownNeededSince.set(Some(time.unsafeNow()))
        scaleManager.scaleCluster(queuedAndRunningWeights = 40, activeNodesCapacity = 100) must beSucceedingIO // between 30% and 80%

        scaleManager.scaleUpCounter.get mustEqual 0
        scaleManager.scaleDownCounter.get mustEqual 0
        scaleManager.scaleUpNeededSince.get must beNone
        scaleManager.scaleDownNeededSince.get must beNone
      }
    }

    "cleanInactiveNodes" should {
      "scale down nodes marked as inactive only when they finished all jobs running on them" in {
        val time = new DummyTime(ZonedDateTime.now())
        val cloudManager = new DummyCloudManager(initialNodesCount = 4)
        val nodeStore = new DummyNodeStore(time, nodeInfoProvider.nodeGroup)
        val scaleManager = new ScaleManagerImpl(config, cloudManager, nodeInfoProvider, nodeStore, time)

        val nodesList = List(
          JobNode(NodeId("test-node-1"), NodeGroup("test-group"), time.unsafeNow().minusMinutes(3), NodeActive(true), NodeVersion("1.0.0")),
          JobNode(NodeId("test-node-2"), NodeGroup("test-group"), time.unsafeNow().minusMinutes(2), NodeActive(true), NodeVersion("1.0.0")),
          JobNode(NodeId("test-node-3"), NodeGroup("test-group"), time.unsafeNow().minusMinutes(2), NodeActive(false), NodeVersion("1.0.0")),
          JobNode(NodeId("test-node-4"), NodeGroup("test-group"), time.unsafeNow().minusMinutes(1), NodeActive(false), NodeVersion("1.0.0"))
        )

        nodeStore.nodesList.set(nodesList)

        scaleManager.cleanInactiveNodes(Set(NodeId("test-node-2"), NodeId("test-node-3"), NodeId("test-node-4"))) must beSucceedingIO
        cloudManager.scaledDownNodes.get must beEmpty

        scaleManager.cleanInactiveNodes(Set(NodeId("test-node-2"), NodeId("test-node-3"))) must beSucceedingIO
        cloudManager.scaledDownNodes.get mustEqual Set(NodeId("test-node-4"))

        scaleManager.cleanInactiveNodes(Set(NodeId("test-node-2"))) must beSucceedingIO
        cloudManager.scaledDownNodes.get mustEqual Set(NodeId("test-node-4"), NodeId("test-node-3"))

        scaleManager.cleanInactiveNodes(Set.empty) must beSucceedingIO
        cloudManager.scaledDownNodes.get mustEqual Set(NodeId("test-node-4"), NodeId("test-node-3"))
      }
    }
  }

}
