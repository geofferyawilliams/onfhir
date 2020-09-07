package io.onfhir.subscription


import akka.{Done, NotUsed}
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.cluster.sharding.external.ExternalShardAllocationStrategy
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import akka.kafka.cluster.sharding.KafkaClusterSharding
import io.onfhir.subscription.config.SubscriptionConfig
import io.onfhir.subscription.model.{FhirNotification, InternalKafkaTopics}
import akka.stream.{ActorMaterializer, Materializer, OverflowStrategy, SourceRef}
import akka.stream.scaladsl.{BroadcastHub, Keep, Sink, Source, SourceQueueWithComplete, StreamRefs}
import io.onfhir.api.{SubscriptionChannelTypes, SubscriptionStatusCodes}
import io.onfhir.subscription.cache.{GetSubscription, GetSubscriptionResponse, RemoveSubscription, Response, SetSubscriptionStatus, UpdateSubscriptionResponse}
import io.onfhir.subscription.model._
import org.reactivestreams.Publisher
import io.onfhir.subscription.channel.{Command, IPushBasedChannelManager, SendNotification}

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.util.{Failure, Success}

object FhirNotificationHandler {
  sealed trait Command extends  CborSerializable {
    val sid:String
    def getEntityId:String = sid
  }

  case class SendFhirNotification(sid:String, resourceContent:String, replyTo:ActorRef[Done]) extends Command

  case class GetFhirNotificationStream(sid:String, replyTo:ActorRef[SourceRef[FhirNotification]]) extends Command
  /**
   * Result of notification sending
   * @param sid           Subscription id
   * @param result        Is notification successfull
   * @param latestStatus  Result of Previous notification attempt
   */
  case class NotificationResult(sid:String, result:Boolean, latestStatus:Int) extends Command

  /**
   * Internal adaptation of responses
   * @param sid
   * @param replyTo
   */
  case class InternalNoSubscription(sid:String, replyTo:ActorRef[Done]) extends Command
  case class InternalSubscriptionResult(sid:String, resource:String, subscription:DDFhirSubscription, replyTo:ActorRef[Done]) extends Command
  case class AdaptedCacheUpdateResponse(sid:String, result:Boolean, f:Option[Throwable] = None) extends Command
  case class StatusUpdateResponse(sid:String, result:Boolean, e:Option[Throwable] = None) extends Command

  /**
   * Initialization of this actor with sharding
   * @param system                Typed actor system
   * @param subscriptionConfig    Configuration
   * @param subscriptionCache     Subscription cache
   * @return
   */
  def init(system: ActorSystem[_],
           subscriptionConfig:
           SubscriptionConfig,
           subscriptionCache:ActorRef[io.onfhir.subscription.cache.Command],
           subscriptionManager: SubscriptionManager,
           pushBasedNotificationChannelActors:Map[String, ActorRef[io.onfhir.subscription.channel.Command]]): Future[ActorRef[Command]] = {
    import system.executionContext

    KafkaClusterSharding(subscriptionConfig.system)
      .messageExtractorNoEnvelope(
        timeout = 10.seconds,
        topic = InternalKafkaTopics.NotificationTopic,
        entityIdExtractor = (msg: Command) => msg.getEntityId,
        settings = subscriptionConfig.kafkaConsumerSettings(ConsumerGroupIds.kafkaConsumerGroupIdForNotifications)
      ).map(messageExtractor => {
        system.log.info("Message extractor created. Initializing sharding for group {}", ConsumerGroupIds.kafkaConsumerGroupIdForNotifications)
        ClusterSharding(system).init(
          Entity(subscriptionConfig.entityTypeKeyForNotification)(createBehavior = _ => FhirNotificationHandler(subscriptionConfig, subscriptionCache, subscriptionManager, pushBasedNotificationChannelActors, system))
          .withAllocationStrategy(new ExternalShardAllocationStrategy(system, subscriptionConfig.entityTypeKeyForNotification.name))
          .withMessageExtractor(messageExtractor))
      })
  }

  //Actor behaviour setup
  def apply(subscriptionConfig: SubscriptionConfig,
            subscriptionCache:ActorRef[io.onfhir.subscription.cache.Command],
            subscriptionManager: SubscriptionManager,
            pushBasedNotificationChannelActors:Map[String, ActorRef[io.onfhir.subscription.channel.Command]], system: ActorSystem[_]): Behavior[Command] = {
    //Create pull based notification queue and publisher for channels like web socket
    implicit val materializer = Materializer.matFromSystem(system)
    val (pullBasedNotificationQueue, notificationPublisher) = Source
      .queue[FhirNotification](100, OverflowStrategy.dropHead)
      .toMat(BroadcastHub.sink /*Sink.asPublisher(true)*/)(Keep.both)
      .run()



    running(subscriptionConfig, subscriptionCache, subscriptionManager, pullBasedNotificationQueue, notificationPublisher, pushBasedNotificationChannelActors)
  }

  /**
   * Actor behaviour
   * @param subscriptionConfig                  Subscription configuration
   * @param subscriptionCache                   Subscription cache
   * @param pullBasedNotificationQueue          Queue for pull based notification handlers (e.g. web socket)
   * @param notificationPublisher               Publisher for pull based notification handlers (e.g. web socket)
   * @param pushBasedNotificationChannelActors  Push based notification handlers by channel type
   * @return
   */
  private def running(
                       subscriptionConfig: SubscriptionConfig,
                       subscriptionCache:ActorRef[io.onfhir.subscription.cache.Command],
                       subscriptionManager: SubscriptionManager,
                       pullBasedNotificationQueue:SourceQueueWithComplete[FhirNotification],
                       notificationPublisher:Source[FhirNotification, NotUsed],
                       pushBasedNotificationChannelActors:Map[String, ActorRef[io.onfhir.subscription.channel.Command]]
                     ): Behavior[Command] = {
    Behaviors.setup { ctx =>
      implicit val materializer: Materializer = Materializer.matFromSystem(ctx.system)
      implicit val executionContext: ExecutionContextExecutor = ctx.executionContext

      implicit val responseTimeout = subscriptionConfig.processorAskTimeout
      Behaviors.receiveMessage[Command] {
        //Handle the
        case SendFhirNotification(sid, resource, replyTo) =>
          ctx.ask[io.onfhir.subscription.cache.Command, io.onfhir.subscription.cache.Response](subscriptionCache, r => GetSubscription(sid, r)) {
            case Success(GetSubscriptionResponse(Some(fs))) => InternalSubscriptionResult(sid, resource , fs, replyTo)
            case Success(GetSubscriptionResponse(None)) => InternalNoSubscription(sid, replyTo)
            case Failure(ex) => InternalNoSubscription(sid, replyTo)
          }
          Behaviors.same

        //Return the notification stream for specific subscription
        case GetFhirNotificationStream(sid, replyTo) =>
          ctx.log.debug(s"Going to handle web socket notifications for subscription $sid")
          val streamRef =
            notificationPublisher
            //Source
            //.fromPublisher(notificationPublisher)
            .filter(_.sid == sid) //Get the stream for the specific subscription
            .runWith(StreamRefs.sourceRef())

          replyTo.tell(streamRef) //return the stream ref
          Behaviors.same


        case InternalSubscriptionResult(sid, resource, fs, replyTo) =>
          fs.channel.channelType match {
            //Web socket is pull based, so we add to our queue
            case SubscriptionChannelTypes.WebSocket =>
              pullBasedNotificationQueue.offer(FhirNotification(fs.id)) //For web sockets we only send a ping message to subscription id
            case _ =>
              pushBasedNotificationChannelActors
                .get(fs.channel.channelType) match {
                case None => ctx.log.warn(s"Communication channel ${fs.channel.channelType} is not supported in onFhir yet!")
                case Some(cha) =>
                  //TODO handle Resource conversion based on mime type
                  val fhirNotification = FhirNotification(sid, fs.channel.endpoint, fs.channel.payload.map(mimeType => resource), fs.channel.headers, fs.status.getValue.intValue())
                  cha.tell(SendNotification(fhirNotification, ctx.self))
              }
          }
          replyTo ! Done
          Behaviors.same

        case InternalNoSubscription(sid, replyTo)=>
          ctx.log.warn("Cannot handle notification, no such subscription {}", sid)
          replyTo ! Done
          Behaviors.same

        /**
         * Async Notification result
          */
        case NotificationResult(sid, result, previousStatus) =>
          def updateSubscriptionStatusInCache(status:Int) =
            ctx.ask[io.onfhir.subscription.cache.Command, io.onfhir.subscription.cache.Response](subscriptionCache, replyTo => SetSubscriptionStatus(sid, status, replyTo)){
              case Success(UpdateSubscriptionResponse(r)) => AdaptedCacheUpdateResponse(sid, r)
              case Failure(f) => AdaptedCacheUpdateResponse(sid, false, Some(f))
            }

          def updateSubscriptionStatus(status:String, error:Option[String] = None) =
            ctx
              .pipeToSelf(subscriptionManager.updateSubscriptionStatus(sid, status, error)) {
                case Success(result) => StatusUpdateResponse(sid, result)
                case Failure(ex) => StatusUpdateResponse(sid, false, Some(ex))
              }

          var status:Option[String] = None
          (result, previousStatus) match {
              //Notification is sent, it was also successful before
              case (true, 1) =>  //DO nothing
              //It was problematic before, not we set it to active
              case (true, _) =>
                ctx.log.debug(s"Notification delivered to the given endpoint for subscription $sid again after last failure...")
                status = Some(SubscriptionStatusCodes.active)
                updateSubscriptionStatusInCache(1)
                updateSubscriptionStatus(SubscriptionStatusCodes.active)
              //Otherwise
              case (false, i) =>
                ctx.log.warn(s"Notification cannot be sent to given endpoint for subscription $sid")
                i match {
                  //It was Ok before, this is the first error
                  case 1  =>
                    status = Some(SubscriptionStatusCodes.error)
                    updateSubscriptionStatusInCache(-1)
                    updateSubscriptionStatus(SubscriptionStatusCodes.error, Some(s"Notification cannot be send to given endpoint for subscription!"))
                  //Second error
                  case r if r > -2 =>
                    updateSubscriptionStatusInCache(r- 1)
                  //If still problematic, remove it
                  case _ =>
                    status = Some(SubscriptionStatusCodes.off)
                    ctx.ask[io.onfhir.subscription.cache.Command, io.onfhir.subscription.cache.Response](subscriptionCache, replyTo => RemoveSubscription(sid, replyTo)){
                      case Success(UpdateSubscriptionResponse(r)) => AdaptedCacheUpdateResponse(sid, r)
                      case Failure(f) => AdaptedCacheUpdateResponse(sid, false, Some(f))
                    }
                    updateSubscriptionStatus(SubscriptionStatusCodes.off, Some("Notification cannot be send to given endpoint successively, subscription is set to off mode. No more notification will be send until you have corrected the endpoint details and update the status as requested again!"))
                }
            }

          Behaviors.same

        case AdaptedCacheUpdateResponse(sid, true, f) => Behaviors.same
        case AdaptedCacheUpdateResponse(sid, false, f) =>
          f match {
            case Some(e) => ctx.log.warn("Subscription status is not updated within the cache due to failure!", e)
            case None => ctx.log.warn("Subscription status is not updated within the cache due to failure!")
          }

          Behaviors.same
        case StatusUpdateResponse(sid, true, _) => Behaviors.same
        case StatusUpdateResponse(sid, false, ex) =>
          ex match {
            case None => ctx.log.warn(s"Subscription status is not updated for the subscription $sid!")
            case Some(e) => ctx.log.warn(s"Subscription status is not updated for the subscription $sid!", e)
          }
          Behaviors.same
      }
    }
  }

}