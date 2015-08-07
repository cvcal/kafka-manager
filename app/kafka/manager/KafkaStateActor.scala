/**
 * Copyright 2015 Yahoo Inc. Licensed under the Apache License, Version 2.0
 * See accompanying LICENSE file.
 */

package kafka.manager

import kafka.api.{PartitionOffsetRequestInfo, OffsetRequest}
import kafka.consumer.SimpleConsumer
import kafka.cluster.Broker
import kafka.common.TopicAndPartition
import kafka.manager.utils.zero81.{ReassignPartitionCommand, PreferredReplicaLeaderElectionCommand}
import org.apache.curator.framework.recipes.cache.PathChildrenCache.StartMode
import org.apache.curator.framework.recipes.cache._
import org.apache.curator.framework.CuratorFramework
import org.joda.time.{DateTimeZone, DateTime}
import kafka.manager.utils.ZkUtils

import scala.collection.mutable
import scala.util.{Success, Failure, Try}

/**
 * @author hiral
 */
import ActorModel._
import kafka.manager.utils._
import scala.collection.JavaConverters._
class KafkaStateActor(curator: CuratorFramework, 
                      deleteSupported: Boolean, 
                      clusterConfig: ClusterConfig) extends BaseQueryCommandActor {

  // e.g. /brokers/topics/analytics_content/partitions/0/state
  private[this] val topicsTreeCache = new TreeCache(curator,ZkUtils.BrokerTopicsPath)

  private[this] val consumersTreeCache = new TreeCache(curator,ZkUtils.ConsumersPath)

  private[this] val topicsConfigPathCache = new PathChildrenCache(curator,ZkUtils.TopicConfigPath,true)

  private[this] val brokersPathCache = new PathChildrenCache(curator,ZkUtils.BrokerIdsPath,true)

  private[this] val adminPathCache = new PathChildrenCache(curator,ZkUtils.AdminPath,true)
  
  private[this] val deleteTopicsPathCache = new PathChildrenCache(curator, ZkUtils.DeleteTopicsPath,true)

  @volatile
  private[this] var topicsTreeCacheLastUpdateMillis : Long = System.currentTimeMillis()

  @volatile
  private[this] var consumersTreeCacheLastUpdateMillis : Long = System.currentTimeMillis()

  private[this] val topicsTreeCacheListener = new TreeCacheListener {
    override def childEvent(client: CuratorFramework, event: TreeCacheEvent): Unit = {
      event.getType match {
        case TreeCacheEvent.Type.INITIALIZED | TreeCacheEvent.Type.NODE_ADDED |
             TreeCacheEvent.Type.NODE_REMOVED | TreeCacheEvent.Type.NODE_UPDATED =>
          topicsTreeCacheLastUpdateMillis = System.currentTimeMillis()
        case _ =>
        //do nothing
      }
    }
  }

  private[this] val consumersTreeCacheListener = new TreeCacheListener {
    override def childEvent(client: CuratorFramework, event: TreeCacheEvent): Unit = {
      event.getType match {
        case TreeCacheEvent.Type.INITIALIZED | TreeCacheEvent.Type.NODE_ADDED |
             TreeCacheEvent.Type.NODE_REMOVED | TreeCacheEvent.Type.NODE_UPDATED =>
          consumersTreeCacheLastUpdateMillis = System.currentTimeMillis()
        case _ =>
        //do nothing
      }
    }
  }

  @volatile
  private[this] var preferredLeaderElection : Option[PreferredReplicaElection] = None

  @volatile
  private[this] var reassignPartitions : Option[ReassignPartitions] = None

  private[this] val adminPathCacheListener = new PathChildrenCacheListener {
    override def childEvent(client: CuratorFramework, event: PathChildrenCacheEvent): Unit = {
      log.info(s"Got event : ${event.getType} path=${Option(event.getData).map(_.getPath)}")
      event.getType match {
        case PathChildrenCacheEvent.Type.INITIALIZED =>
          event.getInitialData.asScala.foreach { cd: ChildData =>
            updatePreferredLeaderElection(cd)
            updateReassignPartition(cd)
          }
        case PathChildrenCacheEvent.Type.CHILD_ADDED | PathChildrenCacheEvent.Type.CHILD_UPDATED =>
          updatePreferredLeaderElection(event.getData)
          updateReassignPartition(event.getData)
        case PathChildrenCacheEvent.Type.CHILD_REMOVED =>
          endPreferredLeaderElection(event.getData)
          endReassignPartition(event.getData)
        case _ =>
          //do nothing
      }
    }

    private[this] def updatePreferredLeaderElection(cd: ChildData): Unit = {
      if(cd != null && cd.getPath.endsWith(ZkUtils.PreferredReplicaLeaderElectionPath)) {
        Try {
          self ! KSUpdatePreferredLeaderElection(cd.getStat.getMtime, cd.getData)
        }
      }
    }

    private[this] def updateReassignPartition(cd: ChildData): Unit = {
      if(cd != null && cd.getPath.endsWith(ZkUtils.ReassignPartitionsPath)) {
        Try {
          self ! KSUpdateReassignPartition(cd.getStat.getMtime, cd.getData)
        }
      }
    }

    private[this] def endPreferredLeaderElection(cd: ChildData): Unit = {
      if(cd != null && cd.getPath.endsWith(ZkUtils.PreferredReplicaLeaderElectionPath)) {
        Try {
          self ! KSEndPreferredLeaderElection(cd.getStat.getMtime)
        }
      }
    }

    private[this] def endReassignPartition(cd: ChildData): Unit = {
      if(cd != null && cd.getPath.endsWith(ZkUtils.ReassignPartitionsPath)) {
        Try {
          self ! KSEndReassignPartition(cd.getStat.getMtime)
        }
      }
    }
  }

  @scala.throws[Exception](classOf[Exception])
  override def preStart() = {
    log.info("Started actor %s".format(self.path))
    log.info("Starting topics tree cache...")
    topicsTreeCache.start()
    log.info("Starting consumers tree cache...")
    consumersTreeCache.start()
    log.info("Starting topics config path cache...")
    topicsConfigPathCache.start(StartMode.BUILD_INITIAL_CACHE)
    log.info("Starting brokers path cache...")
    brokersPathCache.start(StartMode.BUILD_INITIAL_CACHE)
    log.info("Starting admin path cache...")
    adminPathCache.start(StartMode.BUILD_INITIAL_CACHE)
    log.info("Starting delete topics path cache...")
    deleteTopicsPathCache.start(StartMode.BUILD_INITIAL_CACHE)

    log.info("Adding topics tree cache listener...")
    topicsTreeCache.getListenable.addListener(topicsTreeCacheListener)
    log.info("Adding consumers tree cache listener...")
    consumersTreeCache.getListenable.addListener(consumersTreeCacheListener)
    log.info("Adding admin path cache listener...")
    adminPathCache.getListenable.addListener(adminPathCacheListener)
  }

  @scala.throws[Exception](classOf[Exception])
  override def preRestart(reason: Throwable, message: Option[Any]) {
    log.error(reason, "Restarting due to [{}] when processing [{}]",
      reason.getMessage, message.getOrElse(""))
    super.preRestart(reason, message)
  }


  @scala.throws[Exception](classOf[Exception])
  override def postStop(): Unit = {
    log.info("Stopped actor %s".format(self.path))

    log.info("Removing admin path cache listener...")
    Try(adminPathCache.getListenable.removeListener(adminPathCacheListener))
    log.info("Removing consumers tree cache listener...")
    Try(consumersTreeCache.getListenable.removeListener(consumersTreeCacheListener))
    log.info("Removing topics tree cache listener...")
    Try(topicsTreeCache.getListenable.removeListener(topicsTreeCacheListener))

    log.info("Shutting down delete topics path cache...")
    Try(deleteTopicsPathCache.close())
    log.info("Shutting down admin path cache...")
    Try(adminPathCache.close())
    log.info("Shutting down brokers path cache...")
    Try(brokersPathCache.close())
    log.info("Shutting down topics config path cache...")
    Try(topicsConfigPathCache.close())
    log.info("Shutting down consumers tree cache...")
    Try(consumersTreeCache.close())
    log.info("Shutting down topics tree cache...")
    Try(topicsTreeCache.close())

    super.postStop()
  }

  def getTopicDescription(topic: String) : Option[TopicDescription] = {
    val topicPath = "%s/%s".format(ZkUtils.BrokerTopicsPath,topic)
    val descriptionOption : Option[(Int,String)] =
      Option(topicsTreeCache.getCurrentData(topicPath)).map( childData => (childData.getStat.getVersion,asString(childData.getData)))

    for {
      description <- descriptionOption
      partitionsPath = "%s/%s/partitions".format(ZkUtils.BrokerTopicsPath, topic)
      partitions: Map[String, ChildData] <- Option(topicsTreeCache.getCurrentChildren(partitionsPath)).map(_.asScala.toMap)
      states : Map[String, String] = partitions flatMap { case (part, _) =>
        val statePath = s"$partitionsPath/$part/state"
        Option(topicsTreeCache.getCurrentData(statePath)).map(cd => (part, asString(cd.getData)))
      }
      partitionOffsets = getPartitionOffsets(topic, states)
      config = getTopicConfigString(topic)
    } yield TopicDescription(topic, description, Option(states), partitionOffsets, config, deleteSupported)
  }

  private[this] def getTopicConfigString(topic: String) : Option[(Int,String)] = {
    val data: mutable.Buffer[ChildData] = topicsConfigPathCache.getCurrentData.asScala
    val result: Option[ChildData] = data.find(p => p.getPath.endsWith(topic))
    result.map(cd => (cd.getStat.getVersion,asString(cd.getData)))
  }

  def getConsumerList: ConsumerList = {
    withConsumersTreeCache { cache =>
      cache.getCurrentChildren(ZkUtils.ConsumersPath)
    }.fold {
      ConsumerList(IndexedSeq.empty)
    } { data: java.util.Map[String, ChildData] =>
      val filteredList: IndexedSeq[String] = data.asScala.filter{
        case (consumer, childData) =>
          if (clusterConfig.filterConsumers)
            // Defining "inactive consumer" as a consumer that is missing one of three children ids/ offsets/ or owners/
            childData.getStat.getNumChildren > 2
          else true
      }.keySet.toIndexedSeq
      ConsumerList(filteredList)
    }
  }

  def getConsumerDescription(consumer: String) : Option[ConsumerDescription] = {
    val offsetPath = "%s/%s/%s".format(ZkUtils.ConsumersPath,consumer,"offsets")
    val topicOffsetOption : Option[Map[String, ChildData]] = Option(consumersTreeCache.getCurrentChildren(offsetPath)).map(_.asScala.toMap)

    val topicDescriptions: Option[Map[String, ConsumedTopicState]] =
      topicOffsetOption.map[Map[String, ConsumedTopicState]] { _.keys map {
          topic => (topic, getConsumedTopicState(consumer, topic))
        } collect {
          case (topic: String, Some(state: ConsumedTopicState)) => (topic, state)
        } toMap
      }

    topicDescriptions.map(ConsumerDescription(consumer, _))
  }

  private[this] def getConsumedTopicState(consumer:String, topic:String) : Option[ConsumedTopicState] = {
    val offsetPath = "%s/%s/%s/%s".format(ZkUtils.ConsumersPath, consumer, "offsets", topic)
    val ownerPath = "%s/%s/%s/%s".format(ZkUtils.ConsumersPath, consumer, "owners", topic)

    // There might not be topics listed, so we have to take the Option into account
    val partitionOffsets: Option[Map[Int, Long]] = for {
      offsetsByPartition: Map[String, ChildData] <- Option(consumersTreeCache.getCurrentChildren(offsetPath)).map(_.asScala.toMap)
      offsets : Map[Int, Long] = offsetsByPartition map {case (part, data) => (part.toInt, asString(data.getData).toLong)}
    } yield offsets

    val partitionOwners: Option[Map[Int, String]] = for {
      ownersByPartition: Map[String, ChildData] <- Option(consumersTreeCache.getCurrentChildren(ownerPath)).map(_.asScala.toMap)
      owners : Map[Int, String] = ownersByPartition map { case (part, data) => (part.toInt, asString(data.getData)) }
    } yield owners

    val optTopic = getTopicDescription(topic)
    val topicPartitionOffsets = optTopic.map(_.partitionOffsets).getOrElse(Map.empty[Int, Option[Long]])
    // get number of partitions

    val numPartitions: Int = math.max(optTopic.flatMap(_.partitionState.map(_.size)).getOrElse(0),
                                      partitionOffsets.map(_.size).getOrElse(0))

    // Only return a valid ConsumedTopicState if there is state associated with the topic under the consumer
    if (partitionOffsets.isDefined || partitionOwners.isDefined) {
      Some(ConsumedTopicState(consumer,
                              topic,
                              numPartitions,
                              topicPartitionOffsets,
                              partitionOwners.getOrElse(Map.empty),
                              partitionOffsets.getOrElse(Map.empty)))
    } else {
      None
    }
  }

  override def processActorResponse(response: ActorResponse): Unit = {
    response match {
      case any: Any => log.warning("ksa : processActorResponse : Received unknown message: {}", any.toString)
    }
  }


  // conversion between BrokerIdentity and the kafka library's broker case class
  implicit def brokerIdentity2Broker(id : BrokerIdentity) : Broker = {
    Broker(id.id, id.host, id.port)
  }


  // Get the latest offsets for the partitions described in the states map,
  // Code based off of the GetOffsetShell tool in kafka.tools, kafka 0.8.2.1
  private def getPartitionOffsets(topic: String, states: Map[String, String]) : Map[Int,Option[Long]] = {
    val clientId = "partitionOffsetGetter"
    val targetBrokers : IndexedSeq[Broker] = getBrokers.map(brokerIdentity2Broker)
    val time = -1
    val nOffsets = 1

    // Get partition leader broker information
    import org.json4s.jackson.JsonMethods.parse
    import org.json4s.scalaz.JsonScalaz.field
    val partitionsWithLeaders : List[(Int, Option[Broker])] = for {
      (part, state) <- states.toList
      partition = part.toInt
      descJson = parse(state)
      leaderID = field[Int]("leader")(descJson).fold({ e =>
        log.error(s"[topic=$topic] Failed to get partitions from topic json $state"); 0}, identity)
      leader = targetBrokers.find(_.id == leaderID)
    } yield (partition, leader)

    // Get the latest offset for each partition
    val partitionToOffset = for {
      (partitionId, optLeader) <- partitionsWithLeaders.sortBy(_._1)
      partitionOffset: Option[Long] = optLeader match {
        case Some(leader) =>
          val consumer = new SimpleConsumer(leader.host, leader.port, 10000, 100000, clientId)
          val topicAndPartition = TopicAndPartition(topic, partitionId)
          val request = OffsetRequest(Map(topicAndPartition -> PartitionOffsetRequestInfo(time, nOffsets)))
          val offsets = consumer.getOffsetsBefore(request).partitionErrorAndOffsets(topicAndPartition).offsets
          offsets.headOption
        case None => None
      }
    } yield (partitionId, partitionOffset)
    partitionToOffset.toMap
  }

  private[this] def getBrokers : IndexedSeq[BrokerIdentity] = {
    val data: mutable.Buffer[ChildData] = brokersPathCache.getCurrentData.asScala
    data.map { cd =>
      BrokerIdentity.from(nodeFromPath(cd.getPath).toInt, asString(cd.getData))
    }.filter { v =>
      v match {
        case scalaz.Failure(nel) =>
          log.error(s"Failed to parse broker config $nel")
          false
        case _ => true
      }
    }.collect {
      case scalaz.Success(bi) => bi
    }.toIndexedSeq.sortBy(_.id)
  }

  override def processQueryRequest(request: QueryRequest): Unit = {
    request match {
      case KSGetTopics =>
        val deleteSet: Set[String] = {
          if(deleteSupported) {
            val deleteTopicsData: mutable.Buffer[ChildData] = deleteTopicsPathCache.getCurrentData.asScala
            deleteTopicsData.map { cd =>
              nodeFromPath(cd.getPath)
            }.toSet
          } else {
            Set.empty
          }
        }
        withTopicsTreeCache { cache =>
          cache.getCurrentChildren(ZkUtils.BrokerTopicsPath)
        }.fold {
          sender ! TopicList(IndexedSeq.empty, deleteSet)
        } { data: java.util.Map[String, ChildData] =>
          sender ! TopicList(data.asScala.keySet.toIndexedSeq, deleteSet)
        }

      case KSGetConsumers =>
        sender ! getConsumerList

      case KSGetTopicConfig(topic) =>
        sender ! TopicConfig(topic, getTopicConfigString(topic))

      case KSGetTopicDescription(topic) =>
        sender ! getTopicDescription(topic)

      case KSGetTopicDescriptions(topics) =>
        sender ! TopicDescriptions(topics.toIndexedSeq.flatMap(getTopicDescription), topicsTreeCacheLastUpdateMillis)

      case KSGetConsumerDescription(consumer) =>
        sender ! getConsumerDescription(consumer)

      case KSGetConsumedTopicState(consumer, topic) =>
        sender ! getConsumedTopicState(consumer,topic)

      case KSGetConsumerDescriptions(consumers) =>
        sender ! ConsumerDescriptions(consumers.toIndexedSeq.flatMap(getConsumerDescription), consumersTreeCacheLastUpdateMillis)

      case KSGetAllTopicDescriptions(lastUpdateMillisOption) =>
        val lastUpdateMillis = lastUpdateMillisOption.getOrElse(0L)
        if (topicsTreeCacheLastUpdateMillis > lastUpdateMillis) {
          //we have option here since there may be no topics at all!
          withTopicsTreeCache {  cache: TreeCache =>
            cache.getCurrentChildren(ZkUtils.BrokerTopicsPath)
          }.fold {
            sender ! TopicDescriptions(IndexedSeq.empty, topicsTreeCacheLastUpdateMillis)
          } { data: java.util.Map[String, ChildData] =>
            sender ! TopicDescriptions(data.asScala.keys.toIndexedSeq.flatMap(getTopicDescription), topicsTreeCacheLastUpdateMillis)
          }
        } // else no updates to send

      case KSGetAllConsumerDescriptions(lastUpdateMillisOption) =>
        val lastUpdateMillis = lastUpdateMillisOption.getOrElse(0L)
        if (consumersTreeCacheLastUpdateMillis > lastUpdateMillis) {
          sender ! ConsumerDescriptions(getConsumerList.list.flatMap(getConsumerDescription), consumersTreeCacheLastUpdateMillis)
        }

      case KSGetTopicsLastUpdateMillis =>
        sender ! topicsTreeCacheLastUpdateMillis

      case KSGetBrokers =>
        sender ! BrokerList(getBrokers, clusterConfig)

      case KSGetPreferredLeaderElection =>
        sender ! preferredLeaderElection

      case KSGetReassignPartition =>
        sender ! reassignPartitions

      case any: Any => log.warning("ksa : processQueryRequest : Received unknown message: {}", any.toString)
    }
  }

  override def processCommandRequest(request: CommandRequest): Unit = {
    request match {
      case KSUpdatePreferredLeaderElection(millis,json) =>
        safeExecute {
          val s: Set[TopicAndPartition] = PreferredReplicaLeaderElectionCommand.parsePreferredReplicaElectionData(json)
          preferredLeaderElection.fold {
            //nothing there, add as new
            preferredLeaderElection = Some(PreferredReplicaElection(getDateTime(millis), s, None))
          } {
            existing =>
              existing.endTime.fold {
                //update without end? Odd, copy existing
                preferredLeaderElection = Some(existing.copy(topicAndPartition = existing.topicAndPartition ++ s))
              } { _ =>
                //new op started
                preferredLeaderElection = Some(PreferredReplicaElection(getDateTime(millis), s, None))
              }
          }
        }
      case KSUpdateReassignPartition(millis,json) =>
        safeExecute {
          val m : Map[TopicAndPartition, Seq[Int]] = ReassignPartitionCommand.parsePartitionReassignmentZkData(json)
          reassignPartitions.fold {
            //nothing there, add as new
            reassignPartitions = Some(ReassignPartitions(getDateTime(millis),m, None))
          } {
            existing =>
              existing.endTime.fold {
                //update without end? Odd, copy existing
                reassignPartitions = Some(existing.copy(partitionsToBeReassigned = existing.partitionsToBeReassigned ++ m))
              } { _ =>
                //new op started
                reassignPartitions = Some(ReassignPartitions(getDateTime(millis),m, None))
              }
          }
        }
      case KSEndPreferredLeaderElection(millis) =>
        safeExecute {
          preferredLeaderElection.foreach { existing =>
            preferredLeaderElection = Some(existing.copy(endTime = Some(getDateTime(millis))))
          }
        }
      case KSEndReassignPartition(millis) =>
        safeExecute {
          reassignPartitions.foreach { existing =>
            reassignPartitions = Some(existing.copy(endTime = Some(getDateTime(millis))))
          }
        }
      case any: Any => log.warning("ksa : processCommandRequest : Received unknown message: {}", any.toString)
    }
  }

  private[this] def getDateTime(millis: Long) : DateTime = new DateTime(millis,DateTimeZone.UTC)

  private[this] def safeExecute(fn: => Any) : Unit = {
    Try(fn) match {
      case Failure(t) =>
        log.error("Failed!",t)
      case Success(_) =>
        //do nothing
    }
  }

  private[this] def withTopicsTreeCache[T](fn: TreeCache => T) : Option[T] = {
    Option(fn(topicsTreeCache))
  }

  private[this] def withConsumersTreeCache[T](fn: TreeCache => T) : Option[T] = {
    Option(fn(consumersTreeCache))
  }

}

