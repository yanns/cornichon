package com.github.agourlay.cornichon.kafka

import java.time.Duration

import com.github.agourlay.cornichon.core.Session
import com.github.agourlay.cornichon.dsl.CoreDsl
import com.github.agourlay.cornichon.feature.BaseFeature
import com.github.agourlay.cornichon.steps.regular.EffectStep
import org.apache.kafka.clients.producer._
import org.apache.kafka.common.serialization.{ StringDeserializer, StringSerializer }
import com.github.agourlay.cornichon.kafka.KafkaDsl._
import org.apache.kafka.clients.consumer.{ ConsumerConfig, ConsumerRecord, KafkaConsumer }

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import scala.concurrent.{ Future, Promise }

trait KafkaDsl {
  this: BaseFeature with CoreDsl ⇒

  // Kafka scenario can not run in // because they share the same producer/consumer
  override lazy val executeScenariosInParallel: Boolean = false

  val kafkaBootstrapServersHost: String = "localhost"
  val kafkaBootstrapServersPort: Int = 9092

  val kafkaProducerConfig: KafkaProducerConfig = KafkaProducerConfig()
  val kafkaConsumerConfig: KafkaConsumerConfig = KafkaConsumerConfig()

  lazy val featureProducer = producer(s"$kafkaBootstrapServersHost:$kafkaBootstrapServersPort", kafkaProducerConfig)
  lazy val featureConsumer = consumer(s"$kafkaBootstrapServersHost:$kafkaBootstrapServersPort", kafkaConsumerConfig)

  def put_topic(topic: String, key: String, message: String): EffectStep = EffectStep.fromAsync(
    title = s"put message=$message with key=$key to topic=$topic",
    effect = s ⇒ {
      val pr = new ProducerRecord[String, String](topic, key, message)
      val p = Promise[Unit]()
      featureProducer.send(pr, new Callback {
        def onCompletion(metadata: RecordMetadata, exception: Exception): Unit =
          if (exception == null)
            p.success(())
          else
            p.failure(exception)
      })
      p.future.map(_ ⇒ s)
    }
  )

  def read_from_topic(topic: String, amount: Int = 1, timeout: Int = 500): EffectStep = EffectStep.fromAsync(
    title = s"reading the last $amount messages from topic=$topic",
    effect = s ⇒ Future {
      featureConsumer.subscribe(Seq(topic).asJava)
      val messages = ListBuffer.empty[ConsumerRecord[String, String]]
      var nothingNewAnymore = false
      val pollDuration = Duration.ofMillis(timeout.toLong)
      while (!nothingNewAnymore) {
        val newMessages = featureConsumer.poll(pollDuration)
        val collectionOfNewMessages = newMessages.iterator().asScala.toList
        messages ++= collectionOfNewMessages
        nothingNewAnymore = newMessages.isEmpty
      }
      featureConsumer.commitSync()
      messages.drop(messages.size - amount)
      messages.foldLeft(s) { (session, value) ⇒
        commonSessionExtraction(session, topic, value).valueUnsafe
      }
    }
  )

  def kafka(topic: String) = KafkaStepBuilder(
    sessionKey = topic,
    placeholderResolver = placeholderResolver,
    matcherResolver = matcherResolver
  )

  private def commonSessionExtraction(session: Session, topic: String, response: ConsumerRecord[String, String]) =
    session.addValues(
      s"$topic-topic" → response.topic(),
      s"$topic-key" → response.key(),
      s"$topic-value" → response.value()
    )

}

object KafkaDsl {
  import scala.concurrent.ExecutionContext.Implicits.global

  def producer(bootstrapServer: String, producerConfig: KafkaProducerConfig): KafkaProducer[String, String] = {
    val configMap = scala.collection.mutable.Map[String, AnyRef](
      ProducerConfig.BOOTSTRAP_SERVERS_CONFIG -> bootstrapServer,
      ProducerConfig.ACKS_CONFIG -> producerConfig.ack,
      ProducerConfig.BATCH_SIZE_CONFIG -> producerConfig.batchSizeInBytes.toString
    )

    val p = new KafkaProducer[String, String](configMap.asJava, new StringSerializer, new StringSerializer)
    BaseFeature.addShutdownHook(() ⇒ Future {
      p.close()
    })
    p
  }

  def consumer(bootstrapServer: String, consumerConfig: KafkaConsumerConfig): KafkaConsumer[String, String] = {
    val configMap = scala.collection.mutable.Map[String, AnyRef](
      ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG -> bootstrapServer,
      ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG -> "false",
      ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG -> consumerConfig.heartbeatIntervalMsConfig.toString,
      ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG -> consumerConfig.sessionTimeoutMsConfig.toString,
      ConsumerConfig.AUTO_OFFSET_RESET_CONFIG -> "earliest",
      ConsumerConfig.GROUP_ID_CONFIG -> consumerConfig.groupId
    )

    val c = new KafkaConsumer[String, String](configMap.asJava, new StringDeserializer, new StringDeserializer)
    BaseFeature.addShutdownHook(() ⇒ Future {
      c.close()
    })
    c
  }
}

case class KafkaProducerConfig(
    ack: String = "all",
    batchSizeInBytes: Int = 1,
    retriesConfig: Option[Int] = None)

case class KafkaConsumerConfig(
    groupId: String = s"cornichon-groupId",
    sessionTimeoutMsConfig: Int = 10000,
    heartbeatIntervalMsConfig: Int = 100)
