package scorex.core.network

import java.net.{InetAddress, InetSocketAddress}

import com.google.common.primitives.{Bytes, Ints, Longs, Shorts}
import scorex.core.app.{ApplicationVersionSerializer, Version}
import scorex.core.network.peer.LocalAddressPeerFeature
import scorex.core.serialization.Serializer

import scala.util.Try

/**
  * Declared information about peer
  *
  * @param agentName       - Network agent name. May contain information about client code
  *                        stack, starting from core code-base up to the end graphical interface.
  *                        Basic format is `/Name:Version(comments)/Name:Version/.../`,
  *                        e.g. `/Ergo-Scala-client:2.0.0(iPad; U; CPU OS 3_2_1)/AndroidBuild:0.8/`
  * @param protocolVersion - Identifies protocol version being used by the node
  * @param nodeName        - Custom node name
  * @param declaredAddress - Public network address of the node if any
  * @param features        - Set of node capabilities
  */
case class PeerData(agentName: String,
                    protocolVersion: Version,
                    nodeName: String,
                    declaredAddress: Option[InetSocketAddress],
                    features: Seq[PeerFeature]) {

  lazy val localAddressOpt: Option[InetSocketAddress] = {
    features.collectFirst { case LocalAddressPeerFeature(addr) => addr }
  }

  def reachablePeer: Boolean = address.isDefined

  def address: Option[InetSocketAddress] = declaredAddress orElse localAddressOpt

}


class PeerDataSerializer(featureSerializers: PeerFeature.Serializers) extends Serializer[PeerData] {

  // todo what is the real limit?
  private val maxPeerDataSize: Int = 2048

  override def toBytes(obj: PeerData): Array[Byte] = {
    val anb = obj.agentName.getBytes

    val fab = obj.declaredAddress.map { isa =>
      Bytes.concat(isa.getAddress.getAddress, Ints.toByteArray(isa.getPort))
    }.getOrElse(Array[Byte]())

    val nodeNameBytes = obj.nodeName.getBytes

    val featureBytes = obj.features.foldLeft(Array(obj.features.size.toByte)) { case (fb, f) =>
      val featId = f.featureId
      val featBytes = f.bytes
      Bytes.concat(fb, featId +: Shorts.toByteArray(featBytes.length.toShort), featBytes)
    }

    Bytes.concat(
      Array(anb.size.toByte),
      anb,
      obj.protocolVersion.bytes,
      Array(nodeNameBytes.size.toByte),
      nodeNameBytes,
      Ints.toByteArray(fab.length),
      fab,
      featureBytes)
  }

  override def parseBytes(bytes: Array[Byte]): Try[PeerData] = Try {
    require(bytes.length <= maxPeerDataSize)

    var position = 0
    val appNameSize = bytes.head
    require(appNameSize > 0)

    position += 1

    val an = new String(bytes.slice(position, position + appNameSize))
    position += appNameSize

    val av = ApplicationVersionSerializer.parseBytes(
      bytes.slice(position, position + ApplicationVersionSerializer.SerializedVersionLength)).get
    position += ApplicationVersionSerializer.SerializedVersionLength

    val nodeNameSize = bytes.slice(position, position + 1).head
    position += 1

    val nodeName = new String(bytes.slice(position, position + nodeNameSize))
    position += nodeNameSize

    val fas = Ints.fromByteArray(bytes.slice(position, position + 4))
    position += 4

    val isaOpt = if (fas > 0) {
      val fa = bytes.slice(position, position + fas - 4)
      position += fas - 4

      val port = Ints.fromByteArray(bytes.slice(position, position + 4))
      position += 4

      Some(new InetSocketAddress(InetAddress.getByAddress(fa), port))
    } else None

    val featuresCount = bytes.slice(position, position + 1).head
    position += 1

    val feats = (1 to featuresCount).flatMap { _ =>
      val featId = bytes.slice(position, position + 1).head
      position += 1

      val featBytesCount = Shorts.fromByteArray(bytes.slice(position, position + 2))
      position += 2

      //we ignore a feature found in the handshake if we do not know how to parse it or failed to do that

      val featOpt = featureSerializers.get(featId).flatMap { featureSerializer =>
        featureSerializer.parseBytes(bytes.slice(position, position + featBytesCount)).toOption
      }
      position += featBytesCount

      featOpt
    }

    PeerData(an, av, nodeName, isaOpt, feats)
  }

}
