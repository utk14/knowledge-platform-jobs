package org.sunbird.job.mvcindexer.service

import org.slf4j.LoggerFactory
import org.sunbird.job.Metrics
import org.sunbird.job.exception.{APIException, CassandraException, ElasticSearchException}
import org.sunbird.job.mvcindexer.domain.Event
import org.sunbird.job.mvcindexer.task.MVCIndexerConfig
import org.sunbird.job.mvcindexer.util.{ContentUtil, MVCCassandraIndexer, MVCESIndexer}
import org.sunbird.job.util.{CassandraUtil, ElasticSearchUtil, HttpUtil}


class MVCIndexerService(config: MVCIndexerConfig, esUtil: ElasticSearchUtil, httpUtil: HttpUtil, cassandraUtil: CassandraUtil) {

  private[this] lazy val logger = LoggerFactory.getLogger(classOf[MVCIndexerService])
  private val mvcEsIndexer: MVCESIndexer = Option(mvcEsIndexer).getOrElse(new MVCESIndexer(config, esUtil))
  mvcEsIndexer.createMVCSearchIndex()
  private val cassandraManager: MVCCassandraIndexer = new MVCCassandraIndexer(config, cassandraUtil, httpUtil)

  @throws[Exception]
  def processMessage(message: Event)(implicit metrics: Metrics): Unit = {
    try {
      val objectId: String = message.identifier
      if (config.csUpdateAcions.contains(message.action)) {
        if (message.action.equalsIgnoreCase(config.esIndexAction)) {
          message.eventData = ContentUtil.getContentMetaData(message.eventData, objectId, httpUtil, config)
        }
        logger.info("Upsert cassandra document for " + objectId)
        cassandraManager.insertIntoCassandra(message, objectId)
        metrics.incCounter(config.dbUpdateCount)
      }
      logger.info("Elasticsearch document updation for " + objectId)
      mvcEsIndexer.upsertDocument(objectId, message)

      metrics.incCounter(config.successEventCount)
    } catch {
      case ex: APIException => {
        metrics.incCounter(config.apiFailedEventCount)
        throw ex
      }
      case ex: CassandraException => {
        metrics.incCounter(config.dbUpdateFailedCount)
        throw ex
      }
      case ex: ElasticSearchException => {
        metrics.incCounter(config.esFailedEventCount)
        throw ex
      }
    }
  }

  def closeConnection(): Unit = {
    cassandraUtil.close()
  }

}
