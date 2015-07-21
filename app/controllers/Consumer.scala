/**
 * Copyright 2015 Yahoo Inc. Licensed under the Apache License, Version 2.0
 * See accompanying LICENSE file.
 */

package controllers

import kafka.manager.ActorModel.ConsumerIdentity
import kafka.manager.{ApiError, Kafka_0_8_1_1, Kafka_0_8_2_0, Kafka_0_8_2_1}
import play.api.data.validation.{Constraint, Invalid, Valid}
import play.api.mvc._
import scala.util.{Failure, Success, Try}
import scalaz.{-\/, \/-}

/**
 * @author cvcal
 */
object Consumer extends Controller{
  import play.api.libs.concurrent.Execution.Implicits.defaultContext

  private[this] val kafkaManager = KafkaManagerContext.getKafkaManager

/*  val validateName : Constraint[String] = Constraint("validate name") { name =>
    Try {
      kafka.manager.utils.Consumer.validate(name)
    } match {
      case Failure(t) => Invalid(t.getMessage)
      case Success(_) => Valid
    }
  }*/

  def consumers(cluster: String) = Action.async {
    val a = kafkaManager.getConsumerListExtended(cluster)
    a.map { errorOrConsumerList =>
      Ok(views.html.consumer.consumerList(cluster, errorOrConsumerList))
    }
  }

  def consumer(cluster: String, consumerGroup: String) = Action.async {
    kafkaManager.getConsumerIdentity(cluster,consumerGroup).map { errorOrConsumerIdentity =>
      Ok(views.html.consumer.consumerView(cluster,consumerGroup,errorOrConsumerIdentity))
    }
  }

  def consumerAndTopic(cluster: String, consumerGroup: String, topic: String) = TODO //TODO : make the consumed-topic page!

}
