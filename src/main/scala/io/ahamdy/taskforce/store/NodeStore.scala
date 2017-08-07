package io.ahamdy.taskforce.store

import fs2.Task
import io.ahamdy.taskforce.domain.{JobNode, NodeActive, NodeGroup, NodeId}

trait NodeStore {
  def getAllNodes: Task[List[JobNode]]
  def getAllNodesByGroup(groupName: NodeGroup): Task[List[JobNode]] =
    getAllNodes.map(_.filter(_.nodeGroup == groupName))
  def getAllActiveNodesByGroup(groupName: NodeGroup): Task[List[JobNode]] =
    getAllNodesByGroup(groupName).map(_.filter(_.active.value))
  def updateHeartbeat(nodeGroup: NodeGroup, nodeId: NodeId): Task[Unit] = Task.now(())

  def updateGroupNodesStatus(nodeGroup: NodeGroup, active: NodeActive): Task[Unit]
}