/*
 * Copyright 2019 Jia Yu (jiayu2@asu.edu)
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
package org.datasyslab.samplingcube

import java.util.Calendar

import org.apache.spark.sql.SaveMode
import org.apache.spark.storage.StorageLevel
import org.datasyslab.samplingcube.cubes._
import org.datasyslab.samplingcube.datapreparation.PrepFlightData
import org.datasyslab.samplingcube.relatedwork.{SampleFirst, SampleLater}
import org.scalatest.Ignore

@Ignore
class queryworkloadOnFlightTestScala extends testSettings {
  var rawTableName = "inputdf"
  var sampleBudget = 1000
  var sampledAttribute = "AIR_TIME"
  var qualityAttribute = "AIR_TIME"
  var icebergThresholds = Seq(0.1, 0.1)

  describe("Query workload generator on flight test") {
    it("Passed query workload generation step on SampleFirst") {
      var inputDf = spark.read.format("csv").option("delimiter", ",").option("header", "true").load(fligtInputLocation)
      val dataprep = new PrepFlightData
      dataprep.cubeAttributes = dataprep.cubeAttributes.take(numCubedAttributes)
      inputDf = dataprep.prep(inputDf, sampledAttribute, qualityAttribute,predicateDfLocation,true)
      dataprep.queryPredicateDf.show()
      dataprep.totalCount = inputDf.count()

      // Generate approximate 10 queries
      val queryWorkload = dataprep.generateQueryWorkload(workloadSize)

      var factory = new SampleFirst(spark, rawTableName, sampleBudget, dataprep.totalCount)
      inputDf.createOrReplaceTempView(rawTableName)
      factory.build(qualityAttribute)

      var elapsedTime: Long = 0
      var loss = 0.0
      queryWorkload.foreach(f => {
        var startingTime = Calendar.getInstance().getTimeInMillis
        var sample = factory.search(dataprep.cubeAttributes, f.asInstanceOf[Seq[String]], sampledAttribute)
        var endingTime = Calendar.getInstance().getTimeInMillis
        elapsedTime += endingTime - startingTime
        loss += calculateFinalLoss(inputDf, dataprep.cubeAttributes, f.asInstanceOf[Seq[String]], qualityAttribute, sample)(0)
      })
      var avgtimeInterval = elapsedTime / queryWorkload.size
      println(s"avg search time of ${queryWorkload.size} queries =" + avgtimeInterval + " avg final sample loss = " + loss / queryWorkload.length)
    }

    it("Passed query workload generation step on SampleLater") {
      var inputDf = spark.read.format("csv").option("delimiter", ",").option("header", "true").load(fligtInputLocation)
      val dataprep = new PrepFlightData
      dataprep.cubeAttributes = dataprep.cubeAttributes.take(numCubedAttributes)
      inputDf = dataprep.prep(inputDf, sampledAttribute, qualityAttribute,predicateDfLocation, true)
      dataprep.queryPredicateDf.show()
      dataprep.totalCount = inputDf.count()

      // Generate approximate 10 queries
      val queryWorkload = dataprep.generateQueryWorkload(workloadSize)

      var factory = new SampleLater(spark, rawTableName, sampleBudget)
      inputDf.createOrReplaceTempView(rawTableName)

      var elapsedTime: Long = 0
      var loss = 0.0
      queryWorkload.foreach(f => {
        var startingTime = Calendar.getInstance().getTimeInMillis
        var sample = factory.search(dataprep.cubeAttributes, f.asInstanceOf[Seq[String]], sampledAttribute, icebergThresholds)
        var endingTime = Calendar.getInstance().getTimeInMillis
        elapsedTime += endingTime - startingTime
        loss += calculateFinalLoss(inputDf, dataprep.cubeAttributes, f.asInstanceOf[Seq[String]], qualityAttribute, sample)(0)
      })
      var avgtimeInterval = elapsedTime / queryWorkload.size
      println(s"avg search time of ${queryWorkload.size} queries =" + avgtimeInterval + " avg final sample loss = " + loss / queryWorkload.length)
    }

    it("Passed query workload generation step on SamplingCube") {
      var inputDf = spark.read.format("csv").option("delimiter", ",").option("header", "true").load(fligtInputLocation)
      val dataprep = new PrepFlightData
      dataprep.cubeAttributes = dataprep.cubeAttributes.take(numCubedAttributes)
      inputDf = dataprep.prep(inputDf, sampledAttribute, qualityAttribute,predicateDfLocation,true)
      dataprep.queryPredicateDf.show()
      dataprep.totalCount = inputDf.count()

      // Generate approximate 10 queries
      val queryWorkload = dataprep.generateQueryWorkload(workloadSize)

      var factory = new SamplingCube(spark, rawTableName, dataprep.totalCount)
      inputDf.createOrReplaceTempView(rawTableName)
      var cubeTable = factory.buildCube(dataprep.cubeAttributes, sampledAttribute, qualityAttribute, icebergThresholds, dataprep.payload)

      cubeTable.write.mode(SaveMode.Overwrite).option("header", "true").csv(cubeTableOutputLocation)
      cubeTable = spark.read.format("csv").option("delimiter", ",").option("header", "true").load(cubeTableOutputLocation)
      cubeTable = cubeTable.persist(StorageLevel.MEMORY_AND_DISK_SER)

      var elapsedTime: Long = 0
      var loss = 0.0
      queryWorkload.foreach(f => {
        var startingTime = Calendar.getInstance().getTimeInMillis
        var sample = factory.searchCube(cubeTable, dataprep.cubeAttributes, f.asInstanceOf[Seq[String]])
        var endingTime = Calendar.getInstance().getTimeInMillis
        elapsedTime += endingTime - startingTime
        loss += calculateFinalLoss(inputDf, dataprep.cubeAttributes, f.asInstanceOf[Seq[String]], qualityAttribute, sample)(0)
      })
      var avgtimeInterval = elapsedTime / queryWorkload.size
      println(s"avg search time of ${queryWorkload.size} queries =" + avgtimeInterval + " avg final sample loss = " + loss / queryWorkload.length)
    }

    it("Passed query workload generation step on SamplingIcebergCube") {
      var inputDf = spark.read.format("csv").option("delimiter", ",").option("header", "true").load(fligtInputLocation)
      val dataprep = new PrepFlightData
      dataprep.cubeAttributes = dataprep.cubeAttributes.take(numCubedAttributes)
      inputDf = dataprep.prep(inputDf, sampledAttribute, qualityAttribute,predicateDfLocation,true)
      dataprep.queryPredicateDf.show()
      dataprep.totalCount = inputDf.count()

      // Generate approximate 10 queries
      val queryWorkload = dataprep.generateQueryWorkload(workloadSize)

      var factory = new SamplingIcebergCube(spark, rawTableName, dataprep.totalCount)
      inputDf.createOrReplaceTempView(rawTableName)
      var cubeTable = factory.buildCube(dataprep.cubeAttributes, sampledAttribute, qualityAttribute, icebergThresholds, dataprep.payload)

      cubeTable.write.mode(SaveMode.Overwrite).option("header", "true").csv(cubeTableOutputLocation)
      cubeTable = spark.read.format("csv").option("delimiter", ",").option("header", "true").load(cubeTableOutputLocation)
      cubeTable = cubeTable.persist(StorageLevel.MEMORY_AND_DISK_SER)

      println("iceberg cell percent = " + cubeTable.count() * 1.0 / dataprep.totalPredicateCount + "total cells = " + dataprep.totalPredicateCount)

      var elapsedTime: Long = 0
      var loss = 0.0
      queryWorkload.foreach(f => {
        var startingTime = Calendar.getInstance().getTimeInMillis
        var sample = factory.searchCube(cubeTable, dataprep.cubeAttributes, f.asInstanceOf[Seq[String]])
        var endingTime = Calendar.getInstance().getTimeInMillis
        elapsedTime += endingTime - startingTime
        loss += calculateFinalLoss(inputDf, dataprep.cubeAttributes, f.asInstanceOf[Seq[String]], qualityAttribute, sample)(0)
      })
      var avgtimeInterval = elapsedTime / queryWorkload.size
      println(s"avg search time of ${queryWorkload.size} queries =" + avgtimeInterval + " avg final sample loss = " + loss / queryWorkload.length)
    }

//    it("Passed query workload generation step on SamplingIcebergCubeOptSelecFirst") {
//      var inputDf = spark.read.format("csv").option("delimiter", ",").option("header", "true").load(fligtInputLocation)
//      val dataprep = new PrepFlightData
//      dataprep.cubeAttributes = dataprep.cubeAttributes.take(numCubedAttributes)
//      inputDf = dataprep.prep(inputDf, sampledAttribute, qualityAttribute,predicateDfLocation).persist(StorageLevel.MEMORY_AND_DISK_SER)
//      dataprep.queryPredicateDf.show()
//      dataprep.totalCount = inputDf.count()
//
//      // Generate approximate 10 queries
//      val queryWorkload = dataprep.generateQueryWorkload(workloadSize)
//
//      var factory = new SamplingIcebergCubeOptSelecFirst(spark, rawTableName, dataprep.totalCount)
//      inputDf.createOrReplaceTempView(rawTableName)
//
//      //      println("total cells " + dataprep.totalPredicateCount)
//
//      var twoTables = factory.buildCube(dataprep.cubeAttributes, sampledAttribute, qualityAttribute, icebergThresholds, cubeTableOutputLocation, dataprep.payload)
//      twoTables._1.write.mode(SaveMode.Overwrite).option("header", "true").csv(cubeTableOutputLocation)
//      twoTables._2.write.mode(SaveMode.Overwrite).option("header", "true").csv(sampleTableOutputLocation)
//
//      var cubeDf = spark.read.format("csv").option("delimiter", ",").option("header", "true").load(cubeTableOutputLocation).persist(StorageLevel.MEMORY_AND_DISK_SER)
//      var sampleDf = spark.read.format("csv").option("delimiter", ",").option("header", "true").load(sampleTableOutputLocation).persist(StorageLevel.MEMORY_AND_DISK_SER)
//
//      println("iceberg cells percent " + cubeDf.count() * 1.0 / dataprep.totalPredicateCount + " total cells " + dataprep.totalPredicateCount + " numSamples " + sampleDf.count())
//
//      var elapsedTime: Long = 0
//      var loss = 0.0
//      queryWorkload.foreach(f => {
//        var startingTime = Calendar.getInstance().getTimeInMillis
//        var sample = factory.searchCube(cubeDf, dataprep.cubeAttributes, f.asInstanceOf[Seq[String]], sampleDf)
//        var endingTime = Calendar.getInstance().getTimeInMillis
//        elapsedTime += endingTime - startingTime
//        loss += calculateFinalLoss(inputDf, dataprep.cubeAttributes, f.asInstanceOf[Seq[String]], qualityAttribute, sample)
//      })
//      var avgtimeInterval = elapsedTime / queryWorkload.size
//      println(s"avg search time of ${queryWorkload.size} queries =" + avgtimeInterval + " avg final sample loss = " + loss / queryWorkload.length)
//    }
  }
}