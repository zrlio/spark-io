/*
 * Spark-IO: Fast storage and network I/O for Spark
 *
 * Author: Patrick Stuedi <stu@zurich.ibm.com>
 *
 * Copyright (C) 2016, IBM Corporation
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
 *
 */

package org.apache.spark.shuffle.crail

import org.apache.spark._
import org.apache.spark.common._
import org.apache.spark.scheduler.MapStatus
import org.apache.spark.serializer.CrailSerializer
import org.apache.spark.shuffle._
import org.apache.spark.storage._


class CrailShuffleWriter[K, V](
    shuffleBlockManager: CrailShuffleBlockResolver,
    handle: BaseShuffleHandle[K, V, _],
    mapId: Long,
    context: TaskContext)
  extends ShuffleWriter[K, V] with Logging {

  private val dep = handle.dependency
  private val blockManager = SparkEnv.get.blockManager
  private var stopping = false
  private val writeMetrics = context.taskMetrics().shuffleWriteMetrics
  var serializerInstance =  CrailDispatcher.get.getCrailSerializer().newCrailSerializer(dep.serializer)
  var startTime : Double = System.nanoTime() / 1000
  private val shuffle : CrailShuffleWriterGroup = CrailDispatcher.get.getWriterGroup(dep.shuffleId, dep.partitioner.numPartitions, serializerInstance, writeMetrics)
  var initTime : Double = (System.nanoTime()/1000) - startTime
  var runTime : Double = 0
  var initRatio : Double = 0
  var overhead : Double = 0


  /** Write a bunch of records to this task's output */
  override def write(records: Iterator[Product2[K, V]]): Unit = {
    val iter = if (dep.aggregator.isDefined) {
      if (dep.mapSideCombine) {
        dep.aggregator.get.combineValuesByKey(records, context)
      } else {
        records
      }
    } else {
      require(!dep.mapSideCombine, "Map-side combine without Aggregator specified!")
      records
    }

    for (elem <- iter) {
      val bucketId = dep.partitioner.getPartition(elem._1)
      shuffle.writers(bucketId).write(elem._1, elem._2)
    }
  }

  /** Close this writer, passing along whether the map completed */
  override def stop(success: Boolean): Option[MapStatus] = {
    if (stopping) {
      return None
    }
    stopping = true
    if (success) {
      shuffle.purge()
      val sizes: Array[Long] = shuffle.writers.map {writer => writer.length }
      CrailDispatcher.get.releaseWriterGroup(dep.shuffleId, shuffle)
      runTime = (System.nanoTime()/1000) - startTime
      initRatio = runTime/initTime
      overhead = 100/initRatio
      logInfo("shuffler writer: initTime " + initTime + ", runTime " + runTime + ", initRatio " + initRatio + ", overhead " + overhead)
      return Some(MapStatus(blockManager.shuffleServerId, sizes, context.taskAttemptId()))
    } else {
      return None
    }
  }
}
