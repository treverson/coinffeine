package com.coinffeine.acceptance

import org.scalatest.GivenWhenThen
import org.scalatest.fixture
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.ShouldMatchers

import com.coinffeine.acceptance.broker.TestBrokerComponent
import com.coinffeine.acceptance.peer.TestPeerComponent
import com.coinffeine.common.matchers.FutureMatchers

/** Base trait for acceptance testing that includes a test fixture */
trait AcceptanceTest extends fixture.FeatureSpec
  with GivenWhenThen
  with Eventually
  with FutureMatchers
  with ShouldMatchers {

  class TestComponent extends TestPeerComponent with TestBrokerComponent

  override type FixtureParam = TestComponent

  override def withFixture(test: OneArgTest) {
    val component = new TestComponent
    try {
      withFixture(test.toNoArgTest(component))
    } finally {
      component.broker.close()
    }
  }
}