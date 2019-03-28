package scorex.core.network.peer

import java.net.{InetAddress, InetSocketAddress}

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import scorex.core.app.ScorexContext
import scorex.core.network._
import scorex.core.settings.ScorexSettings
import scorex.core.utils.NetworkUtils
import scorex.util.ScorexLogging

import scala.util.Random

/**
  * Peer manager takes care of peers connected and in process, and also chooses a random peer to connect
  * Must be singleton
  */
class PeerManager(settings: ScorexSettings, scorexContext: ScorexContext) extends Actor with ScorexLogging {

  import PeerManager.ReceivableMessages._

  private lazy val peerDatabase = new InMemoryPeerDatabase(settings, scorexContext.timeProvider)

  if (peerDatabase.isEmpty) {
    // fill database with peers from config file if empty
    settings.network.knownPeers.foreach { address =>
      if (!isSelf(address)) {
        peerDatabase.addOrUpdateKnownPeer(PeerInfo.fromAddress(address))
      }
    }
  }

  override def receive: Receive = peerOperations orElse apiInterface

  private def peerOperations: Receive = {
    case AddOrUpdatePeer(peerInfo) =>
      // We have connected to a peer and got his peerInfo from him
      if (!isSelf(peerInfo.peerSpec)) {
        peerDatabase.addOrUpdateKnownPeer(peerInfo)
      }

    case AddToBlacklist(peer, penaltyType) =>
      log.info(s"$peer blacklisted, penalty: $penaltyType")
      peerDatabase.addToBlacklist(peer, penaltyDuration(penaltyType))

    case AddPeerIfEmpty(peerSpec) =>
      // We have received peer data from other peers. It might be modified and should not affect existing data if any
      if (peerSpec.address.forall(a => peerDatabase.get(a).isEmpty) && !isSelf(peerSpec)) {
        val peerInfo: PeerInfo = PeerInfo(peerSpec, 0, None)
        peerDatabase.addOrUpdateKnownPeer(peerInfo)
      }

    case RemovePeer(address) =>
      log.info(s"$address removed")
      peerDatabase.remove(address)

    case get: GetPeers[_] =>
      sender() ! get.choose(peerDatabase.knownPeers, peerDatabase.blacklistedPeers, scorexContext)
  }

  private def apiInterface: Receive = {

    case GetAllPeers =>
      log.trace(s"Get all peers: ${peerDatabase.knownPeers}")
      sender() ! peerDatabase.knownPeers

    case GetBlacklistedPeers =>
      sender() ! peerDatabase.blacklistedPeers

  }

  /**
    * Given a peer's address, returns `true` if the peer is the same is this node.
    */
  private def isSelf(peerAddress: InetSocketAddress): Boolean = {
    NetworkUtils.isSelf(peerAddress, settings.network.bindAddress, scorexContext.externalNodeAddress)
  }

  private def isSelf(peerSpec: PeerSpec): Boolean = {
    peerSpec.declaredAddress.exists(isSelf) || peerSpec.localAddressOpt.exists(isSelf)
  }

  private def penaltyDuration(penalty: PenaltyType): Long = penalty match {
    case PenaltyType.MisbehaviorPenalty => settings.network.misbehaviorBanDuration.toMillis
    case PenaltyType.SpamPenalty => settings.network.spamBanDuration.toMillis
    case PenaltyType.PermanentPenalty => Long.MaxValue
  }

}

object PeerManager {

  object ReceivableMessages {

    case class AddToBlacklist(remote: InetSocketAddress, penaltyType: PenaltyType)

    // peerListOperations messages
    case class AddOrUpdatePeer(data: PeerInfo)

    case class AddPeerIfEmpty(data: PeerSpec)

    case class RemovePeer(address: InetSocketAddress)

    /**
      * Message to get peers from known peers map filtered by `choose` function
      */
    trait GetPeers[T] {
      def choose(knownPeers: Map[InetSocketAddress, PeerInfo],
                 blacklistedPeers: Seq[InetAddress],
                 scorexContext: ScorexContext): T
    }

    /**
      * Choose at most `howMany` random peers, that is connected to our peer or
      * was connected in at most 1 hour ago and wasn't blacklisted.
      */
    case class RecentlySeenPeers(howMany: Int) extends GetPeers[Seq[PeerInfo]] {
      private val TimeDiff: Long = 60 * 60 * 1000

      override def choose(knownPeers: Map[InetSocketAddress, PeerInfo],
                          blacklistedPeers: Seq[InetAddress],
                          sc: ScorexContext): Seq[PeerInfo] = {
        val currentTime = sc.timeProvider.time()
        val recentlySeenNonBlacklisted = knownPeers.values.toSeq
          .filter { p =>
            (p.connectionType.isDefined || currentTime - p.lastSeen > TimeDiff) &&
              !blacklistedPeers.exists(ip => p.peerSpec.declaredAddress.exists(_.getAddress == ip))
          }
        Random.shuffle(recentlySeenNonBlacklisted).take(howMany)
      }
    }

    case object GetAllPeers extends GetPeers[Map[InetSocketAddress, PeerInfo]] {

      override def choose(knownPeers: Map[InetSocketAddress, PeerInfo],
                          blacklistedPeers: Seq[InetAddress],
                          sc: ScorexContext): Map[InetSocketAddress, PeerInfo] = knownPeers
    }

    case class RandomPeerExcluding(excludedPeers: Seq[PeerInfo]) extends GetPeers[Option[PeerInfo]] {

      override def choose(knownPeers: Map[InetSocketAddress, PeerInfo],
                          blacklistedPeers: Seq[InetAddress],
                          sc: ScorexContext): Option[PeerInfo] = {
        val candidates = knownPeers.values.filterNot { p =>
          excludedPeers.exists(_.peerSpec.address == p.peerSpec.address)
        }.toSeq
        if (candidates.nonEmpty) Some(candidates(Random.nextInt(candidates.size)))
        else None
      }
    }

    case object GetBlacklistedPeers extends GetPeers[Seq[InetAddress]] {

      override def choose(knownPeers: Map[InetSocketAddress, PeerInfo],
                          blacklistedPeers: Seq[InetAddress],
                          scorexContext: ScorexContext): Seq[InetAddress] = blacklistedPeers
    }

  }

}

object PeerManagerRef {

  def props(settings: ScorexSettings, scorexContext: ScorexContext): Props = {
    Props(new PeerManager(settings, scorexContext))
  }

  def apply(settings: ScorexSettings, scorexContext: ScorexContext)
           (implicit system: ActorSystem): ActorRef = {
    system.actorOf(props(settings, scorexContext))
  }

  def apply(name: String, settings: ScorexSettings, scorexContext: ScorexContext)
           (implicit system: ActorSystem): ActorRef = {
    system.actorOf(props(settings, scorexContext), name)
  }

}
