package com.bitwise.bitmarket.common.protocol

import java.util.Currency
import scala.util.Random

import akka.actor.{Terminated, ActorRef, Props}
import akka.testkit.{TestActorRef, TestProbe}
import com.googlecode.protobuf.pro.duplex.PeerInfo
import org.scalatest.concurrent.{IntegrationPatience, Eventually}

import com.bitwise.bitmarket.common.{PeerConnection, AkkaSpec}
import com.bitwise.bitmarket.common.currency.{FiatAmount, BtcAmount}
import com.bitwise.bitmarket.common.protocol.protobuf.{BitmarketProtobuf => proto}
import com.bitwise.bitmarket.common.protocol.protobuf.ProtobufConversions
import com.bitwise.bitmarket.common.protorpc.Callbacks

class ProtoRpcMessageGatewayTest
    extends AkkaSpec("MessageGatewaySystem") with Eventually with IntegrationPatience {

  "Protobuf RPC Message gateway" must "send a known message to a remote peer" in new FreshGateway {
    val msg = makeOrderMatch
    gateway ! MessageGateway.ForwardMessage(msg, remotePeerConnection)
    eventually {
      remotePeer.receivedMessagesNumber should be (1)
      remotePeer.receivedMessages contains ProtobufConversions.toProtobuf(msg)
    }
  }

  it must "send a known message twice reusing the connection to the remote peer" in new FreshGateway {
    val (msg1, msg2) = (makeOrderMatch, makeOrderMatch)
    gateway ! MessageGateway.ForwardMessage(msg1, remotePeerConnection)
    gateway ! MessageGateway.ForwardMessage(msg2, remotePeerConnection)
    eventually {
      remotePeer.receivedMessagesNumber should be (2)
      remotePeer.receivedMessages contains ProtobufConversions.toProtobuf(msg1)
      remotePeer.receivedMessages contains ProtobufConversions.toProtobuf(msg2)
    }
  }

  it must "throw while forwarding an unknown message" in new FreshGateway {
    val msg = "This is an unknown message"
    intercept[MessageGateway.ForwardException] {
      testGateway.receive(MessageGateway.ForwardMessage(msg, remotePeerConnection))
    }
  }

  it must "throw while forwarding when recipient was never connected" in new FreshGateway {
    val msg = makeOrderMatch
    remotePeer.shutdown()
    intercept[MessageGateway.ForwardException] {
      testGateway.receive(MessageGateway.ForwardMessage(msg, remotePeerConnection))
    }
  }

  it must "throw while forwarding when recipient was connected and then disconnects" in new FreshGateway {
    val (msg1, msg2) = (makeOrderMatch, makeOrderMatch)
    testGateway.receive(MessageGateway.ForwardMessage(msg1, remotePeerConnection))
    eventually { remotePeer.receivedMessagesNumber should be (1) }
    remotePeer.shutdown()
    testGateway.receive(MessageGateway.ForwardMessage(msg2, remotePeerConnection))
    eventually {
      remotePeer.receivedMessagesNumber should be (1)
      remotePeer.receivedMessages contains ProtobufConversions.toProtobuf(msg1)
    }
  }

  it must "deliver messages to subscribers when filter match" in new FreshGateway {
    val msg = makeOrderMatch
    gateway ! MessageGateway.Subscribe {
      case msg: OrderMatch => true
      case _ => false
    }
    remotePeer.notifyMatchToRemote(ProtobufConversions.toProtobuf(msg))
    expectMsg(msg)
  }

  it must "do not deliver messages to subscribers when filter doesn't match" in new FreshGateway {
    val msg = makeOrderMatch
    gateway ! MessageGateway.Subscribe(msg => false)
    remotePeer.notifyMatchToRemote(ProtobufConversions.toProtobuf(msg))
    expectNoMsg()
  }

  it must "deliver messages to several subscribers when filter match" in new FreshGateway {
    val msg = makeOrderMatch
    val subs = for (i <- 1 to 5) yield TestProbe()
    subs.foreach(_.send(gateway, MessageGateway.Subscribe {
      case msg: OrderMatch => true
      case _ => false
    }))
    remotePeer.notifyMatchToRemote(ProtobufConversions.toProtobuf(msg))
    subs.foreach(_.expectMsg(msg))
  }

  trait MessageUtils {

    def makeOrderMatch: OrderMatch = new OrderMatch(
      id = s"orderId-${Random.nextLong().toHexString}",
      amount = new BtcAmount(BigDecimal(Random.nextDouble())),
      price = new FiatAmount(BigDecimal(Random.nextDouble()), Currency.getInstance("EUR")),
      buyer = s"buyer-${Random.nextLong().toHexString}",
      seller = s"seller-${Random.nextLong().toHexString}"
    )
  }

  trait FreshGateway extends MessageUtils {
    val (localPeerAddress, gateway) = createGateway
    val (remotePeerAddress , remotePeer) = createRemotePeer(localPeerAddress)
    val remotePeerConnection = new PeerConnection(
      remotePeerAddress.getHostName, remotePeerAddress.getPort)
    val testGateway = createGatewayTestActor

    private def createGateway: (PeerInfo, ActorRef) = {
      eventually {
        val peerInfo = new PeerInfo("localhost", randomPort())
        (peerInfo, system.actorOf(Props(new ProtoRpcMessageGateway(peerInfo))))
      }
    }

    private def createGatewayTestActor: TestActorRef[ProtoRpcMessageGateway] = {
      eventually {
        val peerInfo = new PeerInfo("localhost", randomPort())
        TestActorRef(new ProtoRpcMessageGateway(peerInfo))
      }
    }

    private def createRemotePeer(localPeerAddress: PeerInfo): (PeerInfo, TestClient) = {
      eventually {
        val peerInfo = new PeerInfo("localhost", randomPort())
        val client = new TestClient(peerInfo.getPort, localPeerAddress)
        client.connectToServer()
        (peerInfo, client)
      }
    }

    private def randomPort() = Random.nextInt(50000) + 10000
  }
}