package org.sunbird.job.qrimagegenerator.functions

import java.io.File
import java.util

import com.datastax.driver.core.querybuilder.QueryBuilder
import org.apache.commons.lang3.StringUtils
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.functions.ProcessFunction
import org.slf4j.LoggerFactory

import scala.collection.JavaConversions._
import org.sunbird.job.qrimagegenerator.domain.Event
import org.sunbird.job.qrimagegenerator.task.QRCodeImageGeneratorConfig
import org.sunbird.job.{BaseProcessFunction, Metrics}
import org.sunbird.job.util.{ CassandraUtil, CloudStorageUtil }
import org.sunbird.job.qrimagegenerator.util.{ ZipEditorUtil, QRCodeImageGeneratorUtil }
import org.sunbird.job.exception.InvalidEventException

case class DialCodes(data: String, text: String, id: String, location: Option[String])
case class StorageConfig(container: String, path: String, fileName: String)
case class Config(errorCorrectionLevel: Option[String] = Option(""), pixelsPerBlock: Option[Int] = Option(0), qrCodeMargin: Option[Int]= Option(0),
                  textFontName: Option[String] = Option(""), textFontSize: Option[Int]= Option(0), textCharacterSpacing: Option[Double]= Option(0),
                  imageFormat: Option[String]= Option("png"), colourModel: Option[String]= Option(""),imageBorderSize: Option[Int]= Option(0),
                  qrCodeMarginBottom: Option[Int] = Option(0), imageMargin: Option[Int]= Option(0))

case class QRCodeImageGenerator( data: util.List[String] = null, errorCorrectionLevel: String = null,pixelsPerBlock: Int = 0,qrCodeMargin: Int = 0,text: util.List[String] = null,textFontName: String = null,textFontSize: Int = 0,textCharacterSpacing: Double = .0,imageBorderSize: Int = 0,colorModel: String = null,fileName: util.List[String] = null,fileFormat: String = null,qrCodeMarginBottom: Int = 0,imageMargin: Int = 0,tempFilePath: String = null)

case class DialCodesData(dataList: util.ArrayList[String], textList: util.ArrayList[String], fileNameList: util.ArrayList[String])

class QRCodeImageGeneratorFunction(config: QRCodeImageGeneratorConfig,
                                   @transient var cassandraUtil: CassandraUtil = null)
                                  (implicit val stringTypeInfo: TypeInformation[String])
                          extends BaseProcessFunction[Event, String](config) {

    private val LOGGER = LoggerFactory.getLogger(classOf[QRCodeImageGeneratorFunction])
    var cloudStorageUtil: CloudStorageUtil = _
    var qRCodeImageGeneratorUtil: QRCodeImageGeneratorUtil = _

    override def metricsList(): List[String] = {
        List(config.totalEventsCount, config.successEventCount, config.failedEventCount, config.skippedEventCount)
    }

    override def open(parameters: Configuration): Unit = {
        cloudStorageUtil = new CloudStorageUtil(config.cloudStorageType, Option(config.storageKey), Option(config.storageSecret))
        cassandraUtil = new CassandraUtil(config.cassandraHost, config.cassandraPort)
        qRCodeImageGeneratorUtil = new QRCodeImageGeneratorUtil(config, cassandraUtil, cloudStorageUtil)
        super.open(parameters)
    }

    override def close(): Unit = {
        cassandraUtil.close()
        super.close()
    }

    @throws(classOf[InvalidEventException])
    override def processElement(event: Event,
                                context: ProcessFunction[Event, String]#Context,
                                metrics: Metrics): Unit = {
        metrics.incCounter(config.totalEventsCount)

        val availableImages = new util.ArrayList[File]()
        var zipFile: File = null

        try {
            LOGGER.info("QRCodeImageGeneratorService:processMessage: Processing request: " + event);
            LOGGER.info("QRCodeImageGeneratorService:processMessage: Starting message processing at " + System.currentTimeMillis());

            if (event.isValid() && event.isValidDialcodes) {

                val tempFilePath = config.lpTempfileLocation
                var dataList = new util.ArrayList[String]
                var textList = new util.ArrayList[String]
                var fileNameList = new util.ArrayList[String]

                val dialcodesWloc = event.dialCodes.filter(f => !StringUtils.equals(f.getOrElse("location", "").asInstanceOf[String], ""))
                  .foreach{f =>
                      try {
                          val fileName = f.getOrElse("id", "")
                          val fileToSave = new File(tempFilePath + File.separator + fileName + "." + event.imageFormat)
                          LOGGER.info("QRCodeImageGeneratorService:processMessage: creating file - " + fileToSave.getAbsolutePath())
                          fileToSave.createNewFile()
                          LOGGER.info("QRCodeImageGeneratorService:processMessage: created file - " + fileToSave.getAbsolutePath)
                          cloudStorageUtil.downloadFile(f.get("location").get.asInstanceOf[String], fileToSave)
                          availableImages.add(fileToSave)
                      } catch {
                          case e: Exception => throw new InvalidEventException(e.getMessage, Map("partition" -> event.partition, "offset" -> event.offset), e)
                      }
                }

                event.dialCodes.map{f =>
                    dataList.add(f.get("data").get.asInstanceOf[String])
                    textList.add(f.get("text").get.asInstanceOf[String])
                    fileNameList.add(f.get("id").get.asInstanceOf[String])
                }
                val dialCodeDataList = DialCodesData(dataList, textList, fileNameList)

                val qrGenRequest: QRCodeImageGenerator = getQRCodeGenerationRequest(event.imageConfig, dialCodeDataList.dataList, dialCodeDataList.textList, dialCodeDataList.fileNameList)
                val generatedImages = qRCodeImageGeneratorUtil.createQRImages(qrGenRequest, config, event.storageContainer, event.storagePath)
                if (!StringUtils.isBlank(event.processId)) {
                    var zipFileName = event.storageFileName
                    LOGGER.info("QRCodeImageGeneratorService:processMessage: Generating zip for QR codes with processId " + event.processId)
                    if (StringUtils.isBlank(zipFileName)) zipFileName = event.processId
                    availableImages.addAll(generatedImages)
                    zipFile = ZipEditorUtil.zipFiles(availableImages, zipFileName, tempFilePath)
                    val zipDownloadUrl = cloudStorageUtil.uploadFile(event.storageContainer, event.storagePath, zipFile, false)
                    updateCassandra(config.cassandraDialCodeBatchTable, 2, zipDownloadUrl, "processid", event.processId)
                }
                else {
                    LOGGER.info("QRCodeImageGeneratorService:processMessage: Skipping zip creation due to missing processId.")
                }
                LOGGER.info("QRCodeImageGeneratorService:processMessage: Message processed successfully at " + System.currentTimeMillis)
            } else {
                LOGGER.info("QRCodeImageGeneratorService: Eid other than BE_QR_IMAGE_GENERATOR or Dialcodes not present")
                metrics.incCounter(config.skippedEventCount)
            }
        } catch {
            case e: Exception => updateCassandra(config.cassandraDialCodeBatchTable, 3, "", "processid", event.processId)
                LOGGER.info("QRCodeImageGeneratorService:CassandraUpdateFailure: " + e.getMessage)
                throw new InvalidEventException(e.getMessage, Map("partition" -> event.partition, "offset" -> event.offset), e)
        } finally {
            if (null != zipFile)
                {
                    zipFile.delete()
                }
            availableImages.filter(imageFile => null != imageFile).foreach(imageFile => imageFile.delete())
        }
    }

    def getQRCodeGenerationRequest(qrImageconfig: Config, dataList: util.List[String], textList: util.List[String], fileNameList: util.List[String]): QRCodeImageGenerator = {
        QRCodeImageGenerator(dataList, qrImageconfig.errorCorrectionLevel.get, qrImageconfig.pixelsPerBlock.get.asInstanceOf[Integer],
            qrImageconfig.qrCodeMargin.get.asInstanceOf[Integer], textList, qrImageconfig.textFontName.get, qrImageconfig.textFontSize.get,
            qrImageconfig.textCharacterSpacing.get, qrImageconfig.imageBorderSize.get, qrImageconfig.colourModel.get, fileNameList, qrImageconfig.imageFormat.get,
            qrImageconfig.qrCodeMarginBottom.get, qrImageconfig.imageMargin.get, config.lpTempfileLocation)
    }

    def updateCassandra(table: String, status: Int, downloadURL: String, whereClauseKey: String, whereClauseValue: String): Unit = {
        val updateQuery: String  = QueryBuilder.update(config.cassandraKeyspace, table)
          .`with`(QueryBuilder.set("status", status))
          .and(QueryBuilder.set("url", downloadURL))
          .where(QueryBuilder.eq(whereClauseKey, whereClauseValue)).toString
        cassandraUtil.executeQuery(updateQuery)
    }
}