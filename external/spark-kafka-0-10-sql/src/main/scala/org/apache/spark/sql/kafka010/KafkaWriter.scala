/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.kafka010

import java.{util => ju}

import org.apache.spark.internal.Logging
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.execution.{QueryExecution, SQLExecution}
import org.apache.spark.sql.kafka010.UmsCommon._
import org.apache.spark.sql.types.{BinaryType, StringType}
import org.apache.spark.sql.{AnalysisException, SparkSession}
import org.apache.spark.util.Utils

import scala.collection.JavaConverters._

/**
  * The [[KafkaWriter]] class is used to write data from a batch query
  * or structured streaming query, given by a [[QueryExecution]], to Kafka.
  * The data is assumed to have a value column, and an optional topic and key
  * columns. If the topic column is missing, then the topic must come from
  * the 'topic' configuration option. If the key column is missing, then a
  * null valued key field will be added to the
  * [[org.apache.kafka.clients.producer.ProducerRecord]].
  */
private[kafka010] object KafkaWriter extends Logging {
  val TOPIC_ATTRIBUTE_NAME: String = "topic"
  val KEY_ATTRIBUTE_NAME: String = "key"
  val VALUE_ATTRIBUTE_NAME: String = "value"

  implicit def toScalaMap(javaMap: ju.Map[String, String]): Map[String, String] = javaMap.asScala.toMap

  override def toString: String = "KafkaWriter"

  def validateQuery(
                     queryExecution: QueryExecution,
                     kafkaParameters: Map[String, Object],
                     topic: Option[String] = None): Unit = {
    val schema = queryExecution.analyzed.output
    schema.find(_.name == TOPIC_ATTRIBUTE_NAME).getOrElse(
      if (topic.isEmpty) {
        throw new AnalysisException(s"topic option required when no " +
          s"'$TOPIC_ATTRIBUTE_NAME' attribute is present. Use the " +
          s"${KafkaSourceProvider.TOPIC_OPTION_KEY} option for setting a topic.")
      } else {
        Literal(topic.get, StringType)
      }
    ).dataType match {
      case StringType => // good
      case _ =>
        throw new AnalysisException(s"Topic type must be a String")
    }
    schema.find(_.name == KEY_ATTRIBUTE_NAME).getOrElse(
      Literal(null, StringType)
    ).dataType match {
      case StringType | BinaryType => // good
      case _ =>
        throw new AnalysisException(s"$KEY_ATTRIBUTE_NAME attribute type " +
          s"must be a String or BinaryType")
    }
    schema.find(_.name == VALUE_ATTRIBUTE_NAME).getOrElse(
      throw new AnalysisException(s"Required attribute '$VALUE_ATTRIBUTE_NAME' not found")
    ).dataType match {
      case StringType | BinaryType => // good
      case _ =>
        throw new AnalysisException(s"$VALUE_ATTRIBUTE_NAME attribute type " +
          s"must be a String or BinaryType")
    }
  }

  private def validateUmsParams(umsParameters: Map[String, String]): Unit = {
    if (!umsParameters.contains(UMS_PROTOCOL)) {
      throw new AnalysisException(s"Required ums config '$UMS_PROTOCOL' not found")
    } else {
      if (!validateProtocol(umsParameters(UMS_PROTOCOL))) {
        throw new AnalysisException(s"'$UMS_PROTOCOL' config value '${umsParameters(UMS_PROTOCOL)}' is not valid")
      }
    }

    if (!umsParameters.contains(UMS_NAMESPACE)) {
      throw new AnalysisException(s"Required ums config '$UMS_NAMESPACE' not found")
    } else {
      if (!validateNamespace(umsParameters(UMS_NAMESPACE))) {
        throw new AnalysisException(s"'$UMS_NAMESPACE' config value '${umsParameters(UMS_NAMESPACE)}' is not valid")
      }
    }

    if (!umsParameters.contains(UMS_SCHEMA)) {
      throw new AnalysisException(s"Required ums config '$UMS_SCHEMA' not found")
    }
  }

  def write(
             sparkSession: SparkSession,
             queryExecution: QueryExecution,
             kafkaParameters: ju.Map[String, String],
             umsParameters: ju.Map[String, String],
             topic: Option[String] = None): Unit = {
    if (umsParameters.contains(DATA_FORMAT) && umsParameters(DATA_FORMAT) == UMS_DATA_FORMAT) {
      validateUmsParams(umsParameters)
      val schema = genSparkStructType(umsParameters(UMS_SCHEMA))
      SQLExecution.withNewExecutionId(sparkSession, queryExecution) {
        queryExecution.toRdd.foreachPartition { iter =>
          val writeTask = new KafkaWriteTask(kafkaParameters, umsParameters, schema, topic)
          Utils.tryWithSafeFinally(block = writeTask.umsExecute(iter))(
            finallyBlock = writeTask.close())
        }
      }
    } else {
      validateQuery(queryExecution, kafkaParameters, topic)
      val schema = queryExecution.analyzed.schema
      SQLExecution.withNewExecutionId(sparkSession, queryExecution) {
        queryExecution.toRdd.foreachPartition { iter =>
          val writeTask = new KafkaWriteTask(kafkaParameters, umsParameters, schema, topic)
          Utils.tryWithSafeFinally(block = writeTask.execute(iter))(
            finallyBlock = writeTask.close())
        }
      }
    }
  }

}
