/*
 * Copyright 2018 PayPal Inc.
 *
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

package com.paypal.gimel.common.catalog

import scala.collection.JavaConverters._
import scala.collection.immutable.Map
import scala.language.implicitConversions

import org.apache.hadoop.hive.conf.HiveConf
import org.apache.hadoop.hive.metastore.HiveMetaStoreClient
import org.apache.hadoop.hive.metastore.api.Table
import spray.json._

import com.paypal.gimel.common.catalog.GimelCatalogJsonProtocol._
import com.paypal.gimel.common.conf.{CatalogProviderConfigs, CatalogProviderConstants}
import com.paypal.gimel.common.conf.GimelConstants
import com.paypal.gimel.logger.Logger

case class Field(fieldName: String,
                 fieldType: String = "string",
                 isFieldNullable: Boolean = true,
                 partitionStatus: Boolean = false,
                 columnIndex: Int = 0
                )

case class DataSetProperties(datasetType: String,
                             fields: Array[Field],
                             partitionFields: Array[Field],
                             props: Map[String, String]
                            )

object CatalogProvider {

  val logger = Logger(this.getClass.getName)
  val servUtils = com.paypal.gimel.common.gimelservices.GimelServiceUtilities()

  /**
    * Creates DataSetProperties and Returns to caller
    * Properties are Fetched from specified CatalogProvider
    *
    * @param datasetName DataSet Name
    * @param options     User Props
    * @return DataSetProperties
    */

  def getDataSetProperties(datasetName: String,
                           options: Map[String, Any] = Map[String, Any]()): DataSetProperties = {
    def MethodName: String = new Exception().getStackTrace().apply(1).getMethodName()
    logger.info(" @Begin --> " + MethodName)

    // The user options are passed which will override the default service util properties
    if (!options.isEmpty) {
      servUtils.customize(options.map { x => (x._1, x._2.toString) })
    }

    val resolvedSourceTable = resolveDataSetName(datasetName)
    val suppliedCatalogProvider: String = options.getOrElse(CatalogProviderConfigs.CATALOG_PROVIDER, CatalogProviderConstants.PRIMARY_CATALOG_PROVIDER).toString

    val catalogProvider: String = (suppliedCatalogProvider.equalsIgnoreCase(CatalogProviderConstants.HIVE_PROVIDER), datasetName.split('.').length > 2) match {
      case (true, true) =>
        logger.warning("******************************** WARNING *************************************")
        logger.warning("It seems the DataSet Name is not regular pattern such as [DB.TBL]")
        logger.warning(s"However, supplied catalog provider [${suppliedCatalogProvider} is an incompatible combination.]")
        logger.warning(s"Hence auto OVER-RIDING catalog provider = ${CatalogProviderConstants.PRIMARY_CATALOG_PROVIDER}")
        logger.warning("******************************** WARNING *************************************")
        CatalogProviderConstants.PRIMARY_CATALOG_PROVIDER
      case (false, false) =>
        if (suppliedCatalogProvider.equalsIgnoreCase(CatalogProviderConstants.USER_PROVIDER)) suppliedCatalogProvider
        else {
          logger.warning("******************************** WARNING *************************************")
          logger.warning("It seems the DataSet Name is of  pattern such as [DB.TBL]")
          logger.warning(s"However, supplied catalog provider [${suppliedCatalogProvider} is an incompatible combination.]")
          logger.warning(s"Hence auto OVER-RIDING catalog provider = ${CatalogProviderConstants.HIVE_PROVIDER}")
          logger.warning("******************************** WARNING *************************************")
          CatalogProviderConstants.HIVE_PROVIDER
        }
      case _ => suppliedCatalogProvider
    }
    logger.info(s"Resolved Catalog Provider is --> ${catalogProvider}")
    val datasetProps = catalogProvider.toUpperCase() match {
      case GimelConstants.USER =>
        logger.info(s"Resolving Catalog Via catalogProvider --> ${catalogProvider}")
        val props = getUserProps(options(datasetName + "." + GimelConstants.DATASET_PROPS))
        logger.info(s"User Supplied Props --> ${props}")
        props
      case CatalogProviderConstants.PCATALOG_PROVIDER | CatalogProviderConstants.UDC_PROVIDER =>
        val db = resolvedSourceTable.split('.').head
        val dataset = resolvedSourceTable.split('.').tail.mkString(".")
        db.equalsIgnoreCase(GimelConstants.PCATALOG_STRING) | db.equalsIgnoreCase(CatalogProviderConstants.UDC_PROVIDER) match {
          case true =>
            if (servUtils.checkIfDataSetExists(dataset)) {
              servUtils.getDataSetProperties(dataset)
            }
            else {
              servUtils.getDynamicDataSetProperties(dataset, options)
            }
          case false =>
            logger.info(
              s"""
                 |Non-Gimel DataSet --> ${resolvedSourceTable}.
                 |Resolving Props via catalogProvider --> ${CatalogProviderConstants.HIVE_PROVIDER}
               """.stripMargin)
            getDataSetPropertiesFromHive(resolvedSourceTable)
        }
      case CatalogProviderConstants.HIVE_PROVIDER =>
        logger.info(s"Resolving Catalog Via catalogProvider --> ${catalogProvider}")
        getDataSetPropertiesFromHive(resolvedSourceTable)
      case other =>
        throw new Exception(s"Unknown CatalogProvider --> ${catalogProvider}")
    }
    logger.info(s"Received Properties --> ${datasetProps}")

    val newProps = datasetProps.props ++
      Map("gimel.kafka.avro.schema.string" -> datasetProps.props.getOrElse("gimel.kafka.avro.schema.string", "").replace("\\", "\"")) ++ options.mapValues(_.toString)
    DataSetProperties(datasetProps.datasetType, datasetProps.fields, datasetProps.partitionFields, newProps)

  }

  /**
    * Creates DataSetProperties provided by user and Returns to caller
    *
    * @param x User provided properties (DataSetProperties or Json String)
    * @return DataSetProperties
    */

  def getUserProps(x: Any): DataSetProperties = {
    x match {
      case str: String =>
        str.parseJson.convertTo[DataSetProperties]
      case dataSetProps: DataSetProperties =>
        dataSetProps
      case _ =>
        val examplesString =
          """|
            |{
            |    "datasetType": "KAFKA",
            |    "fields": [{
            |            "fieldName": "id",
            |            "fieldType": "1",
            |            "isFieldNullable": false
            |        },
            |        {
            |            "fieldName": "name",
            |            "fieldType": "john",
            |            "isFieldNullable": true
            |        }
            |    ],
            |    "partitionFields": [],
            |    "props": {
            |        "key.deserializer": "org.apache.kafka.common.serialization.StringDeserializer",
            |        "auto.offset.reset": "earliest",
            |        "gimel.kafka.checkpoint.zookeeper.host": "zookeeper:2181",
            |        "gimel.kafka.whitelist.topics": "kafka_topic",
            |        "datasetName": "dummy",
            |        "gimel.kafka.throttle.batch.fetchRowsOnFirstRun": "250",
            |        "gimel.kafka.throttle.batch.maxRecordsPerPartition": "25000000",
            |        "gimel.kafka.throttle.batch.batch.parallelsPerPartition": "250",
            |        "value.deserializer": "org.apache.kafka.common.serialization.ByteArrayDeserializer",
            |        "value.serializer": "org.apache.kafka.common.serialization.ByteArraySerializer",
            |        "gimel.kafka.checkpoint.zookeeper.path": "/pcatalog/kafka_consumer/checkpoint",
            |        "gimel.kafka.avro.schema.source.url": "http://schema_registry:8081",
            |        "key.serializer": "org.apache.kafka.common.serialization.StringSerializer",
            |        "gimel.kafka.avro.schema.source.wrapper.key": "schema_registry_key",
            |        "gimel.kafka.bootstrap.servers": "localhost:9092"
            |    }
            |}
            | """.stripMargin.trim
        val errorMessageForClient =
          s"""
             |Invalid props type ${x.getClass.getCanonicalName}.
             |Supported types are eitherMap DataSetProperties OR Json String.
             |Valid example for String --> $examplesString
          """.stripMargin
        throw new Exception(errorMessageForClient)
    }
  }

  /**
    * Creates DataSetProperties from Catalog Provider - HIVE
    *
    * @param hiveTableName Hive Table Name
    * @return DataSetProperties
    */

  def getDataSetPropertiesFromHive(hiveTableName: String): DataSetProperties = {
    def MethodName: String = new Exception().getStackTrace().apply(1).getMethodName()
    logger.info(" @Begin --> " + MethodName)

    val hiveTable = getHiveTable(hiveTableName)
    val Array(nameSpace, datasetName) = hiveTableName.split('.')
    val tableProps: Map[String, String] = hiveTable.getParameters.asScala.toMap
    val tableStorageDescriptor = hiveTable.getSd
    val schema: Array[String] =
      tableStorageDescriptor.getCols.asScala.map(x => x.getName).toArray
    val serDeParameters =
      tableStorageDescriptor.getSerdeInfo.getParameters.asScala
    val props2 = (tableProps
      ++ Map(
      CatalogProviderConstants.PROPS_LOCATION -> hiveTable.getSd.getLocation,
      CatalogProviderConstants.PROPS_NAMESPACE -> nameSpace,
      CatalogProviderConstants.DATASET_PROPS_DATASET -> datasetName
    )
      ++ serDeParameters
      )

    val fieldsInTable =
      tableStorageDescriptor.getCols.asScala.map(x => Field(x.getName, x.getType)).toArray
    val partitionsInTable =
      hiveTable.getPartitionKeys.asScala.map(x => Field(x.getName, x.getType)).toArray
    val datasetProps =
      DataSetProperties(tableProps.getOrElse(GimelConstants.STORAGE_TYPE, GimelConstants.NONE_STRING),
        fieldsInTable,
        partitionsInTable,
        props2)

    datasetProps
  }

  /**
    * Utility to get Hive Table Object for a given table in hive
    *
    * @param tableName Hive Table Name
    * @return Table
    */

  def getHiveTable(tableName: String): Table = {
    def MethodName: String = new Exception().getStackTrace().apply(1).getMethodName()
    logger.info(" @Begin --> " + MethodName)

    val hiveClient = new HiveMetaStoreClient(new HiveConf())
    val Array(hiveDataBase, hiveTable) = tableName.split('.')
    val hiveTableProps: Table = hiveClient.getTable(hiveDataBase, hiveTable)
    hiveClient.close()
    hiveTableProps
  }

  /**
    * Add Data Base tag if it is missing in the DataSet Name
    *
    * @param sourceName DataSet Name
    * @return Resolve DataSet Name
    */

  def resolveDataSetName(sourceName: String): String = {
    def MethodName: String = new Exception().getStackTrace().apply(1).getMethodName()
    logger.info(" @Begin --> " + MethodName)

    if (sourceName.contains('.')) {
      sourceName
    } else {
      s"${GimelConstants.DEFAULT_STRING}.$sourceName"
    }
  }

}
