package org.sunbird.job.publish.helpers

import org.sunbird.job.util.CassandraUtil
import org.sunbird.publish.helpers.{ObjectReader, ObjectValidator}

trait QuestionSetPublisher extends ObjectReader with ObjectValidator {

	override def getExtData(identifier: String)(implicit cassandraUtil: CassandraUtil): Option[Map[String, AnyRef]] = None

	def getHierarchy(identifier: String)(implicit cassandraUtil: CassandraUtil): Option[Map[String, AnyRef]] = {
		//TODO: Implement the method
		None
	}

	//TODO: additional method for questionset publish goes here
}