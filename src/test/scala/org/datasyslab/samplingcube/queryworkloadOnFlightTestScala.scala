/*
 * Copyright (c) 2019 - 2020 Data Systems Lab at Arizona State University
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

import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.sql.SaveMode
import org.apache.spark.storage.StorageLevel
import org.datasyslab.samplingcube.cubes._
import org.datasyslab.samplingcube.datapreparation.PrepFlightData
import org.datasyslab.samplingcube.relatedwork.{SampleFirst, SampleLater}
import org.datasyslab.samplingcube.utils.SimplePoint
import org.scalatest.Ignore

@Ignore
class queryworkloadOnFlightTestScala extends testSettings {
  var rawTableName = "inputdf"
  var sampleBudget = 1000
  var sampledAttribute = "AIR_TIME"
  var icebergThresholds = 0.1

  describe("Query workload generator on flight test") {
    it("Passed query workload generation step on SampleFirst") {
      var inputDf = spark.read.format("csv").option("delimiter", ",").option("header", "true").load(fligtInputLocation)
      val dataprep = new PrepFlightData
      dataprep.cubeAttributes = dataprep.cubeAttributes.take(numCubedAttributes)
      inputDf = dataprep.prep(inputDf, sampledAttribute,predicateDfLocation)
      dataprep.queryPredicateDf.show()
      dataprep.totalCount = inputDf.count()

      // Generate approximate 10 queries
      val queryWorkload = dataprep.generateQueryWorkload(workloadSize)

      var factory = new SampleFirst(spark, rawTableName, sampleBudget, dataprep.totalCount)
      inputDf.createOrReplaceTempView(rawTableName)
      factory.build()

      var elapsedTime: Long = 0
      var loss = 0.0
      queryWorkload.foreach(f => {
        var startingTime = Calendar.getInstance().getTimeInMillis
        var sample = factory.search(dataprep.cubeAttributes, f.asInstanceOf[Seq[String]], sampledAttribute)
        var endingTime = Calendar.getInstance().getTimeInMillis
        elapsedTime += endingTime - startingTime
        loss += calculateFinalLoss(inputDf, dataprep.cubeAttributes, f.asInstanceOf[Seq[String]], sampledAttribute, sample)(0)
      })
      var avgtimeInterval = elapsedTime / queryWorkload.size
      println(s"avg search time of ${queryWorkload.size} queries =" + avgtimeInterval + " avg final sample loss = " + loss / queryWorkload.length)
    }

    it("Passed query workload generation step on SampleLater") {
      var inputDf = spark.read.format("csv").option("delimiter", ",").option("header", "true").load(fligtInputLocation)
      val dataprep = new PrepFlightData
      dataprep.cubeAttributes = dataprep.cubeAttributes.take(numCubedAttributes)
      inputDf = dataprep.prep(inputDf, sampledAttribute, predicateDfLocation)
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
        loss += calculateFinalLoss(inputDf, dataprep.cubeAttributes, f.asInstanceOf[Seq[String]], sampledAttribute, sample)(0)
      })
      var avgtimeInterval = elapsedTime / queryWorkload.size
      println(s"avg search time of ${queryWorkload.size} queries =" + avgtimeInterval + " avg final sample loss = " + loss / queryWorkload.length)
    }

    it("Passed query workload generation step on SamplingCube") {
      var inputDf = spark.read.format("csv").option("delimiter", ",").option("header", "true").load(fligtInputLocation)
      val dataprep = new PrepFlightData
      dataprep.cubeAttributes = dataprep.cubeAttributes.take(numCubedAttributes)
      inputDf = dataprep.prep(inputDf, sampledAttribute, predicateDfLocation)
      dataprep.queryPredicateDf.show()
      dataprep.totalCount = inputDf.count()

      // Generate approximate 10 queries
      val queryWorkload = dataprep.generateQueryWorkload(workloadSize)

      var factory = new SamplingCube(spark, rawTableName, dataprep.totalCount)
      inputDf.createOrReplaceTempView(rawTableName)
      var twoTables = factory.buildCube(dataprep.cubeAttributes, sampledAttribute, icebergThresholds, dataprep.payload)

      twoTables._1.write.mode(SaveMode.Overwrite).option("header", "true").csv(cubeTableOutputLocation)
      val fs = FileSystem.get(spark.sparkContext.hadoopConfiguration)
      val outPutPath = new Path(globalSamTableOutputLocation)
      if (fs.exists(outPutPath)) fs.delete(outPutPath, true)
      twoTables._2.saveAsObjectFile(globalSamTableOutputLocation)

      var cubeDf = spark.read.format("csv").option("delimiter", ",").option("header", "true").load(cubeTableOutputLocation).persist(StorageLevel.MEMORY_AND_DISK_SER)
      var globalSamRdd = spark.sparkContext.objectFile[SimplePoint](globalSamTableOutputLocation)

      val cubeLoader = new CubeLoader(Seq(cubeDf), globalSamRdd)
      var elapsedTime: Long = 0
      var loss = 0.0
      queryWorkload.foreach(f => {
        var startingTime = Calendar.getInstance().getTimeInMillis
        var sample = cubeLoader.searchCube(dataprep.cubeAttributes, f.asInstanceOf[Seq[String]])._2
        var endingTime = Calendar.getInstance().getTimeInMillis
        elapsedTime += endingTime - startingTime
        loss += calculateFinalLoss(inputDf, dataprep.cubeAttributes, f.asInstanceOf[Seq[String]], sampledAttribute, sample)(0)
      })
      var avgtimeInterval = elapsedTime / queryWorkload.size
      println(s"avg search time of ${queryWorkload.size} queries =" + avgtimeInterval + " avg final sample loss = " + loss / queryWorkload.length)
    }

    it("Passed query workload generation step on SamplingIcebergCube") {
      var inputDf = spark.read.format("csv").option("delimiter", ",").option("header", "true").load(fligtInputLocation)
      val dataprep = new PrepFlightData
      dataprep.cubeAttributes = dataprep.cubeAttributes.take(numCubedAttributes)
      inputDf = dataprep.prep(inputDf, sampledAttribute, predicateDfLocation)
      dataprep.queryPredicateDf.show()
      dataprep.totalCount = inputDf.count()

      // Generate approximate 10 queries
      val queryWorkload = dataprep.generateQueryWorkload(workloadSize)

      var factory = new SamplingIcebergCube(spark, rawTableName, dataprep.totalCount)
      inputDf.createOrReplaceTempView(rawTableName)
      var twoTables = factory.buildCube(dataprep.cubeAttributes, sampledAttribute, icebergThresholds, dataprep.payload)

      twoTables._1.write.mode(SaveMode.Overwrite).option("header", "true").csv(cubeTableOutputLocation)
      val fs = FileSystem.get(spark.sparkContext.hadoopConfiguration)
      val outPutPath = new Path(globalSamTableOutputLocation)
      if (fs.exists(outPutPath)) fs.delete(outPutPath, true)
      twoTables._2.saveAsObjectFile(globalSamTableOutputLocation)

      var cubeDf = spark.read.format("csv").option("delimiter", ",").option("header", "true").load(cubeTableOutputLocation).persist(StorageLevel.MEMORY_AND_DISK_SER)
      var globalSamRdd = spark.sparkContext.objectFile[SimplePoint](globalSamTableOutputLocation)

      println("iceberg cell percent = " + cubeDf.count() * 1.0 / dataprep.totalPredicateCount + "total cells = " + dataprep.totalPredicateCount)

      val cubeLoader = new CubeLoader(Seq(cubeDf), globalSamRdd)
      var elapsedTime: Long = 0
      var loss = 0.0
      queryWorkload.foreach(f => {
        var startingTime = Calendar.getInstance().getTimeInMillis
        var sample = cubeLoader.searchCube(dataprep.cubeAttributes, f.asInstanceOf[Seq[String]])._2
        var endingTime = Calendar.getInstance().getTimeInMillis
        elapsedTime += endingTime - startingTime
        loss += calculateFinalLoss(inputDf, dataprep.cubeAttributes, f.asInstanceOf[Seq[String]], sampledAttribute, sample)(0)
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
