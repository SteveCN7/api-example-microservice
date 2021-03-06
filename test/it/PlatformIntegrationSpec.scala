/*
 * Copyright 2018 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package it

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.core.WireMockConfiguration._
import uk.gov.hmrc.hello.domain.Registration
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterEach, TestData}
import org.scalatestplus.play.guice.GuiceOneAppPerTest
import play.api.http.LazyHttpErrorHandler
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import play.api.test.{FakeRequest, Helpers}
import play.api.{Application, Mode}
import uk.gov.hmrc.hello.controllers.Documentation
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.play.microservice.filters.MicroserviceFilterSupport

class PlatformIntegrationSpec extends UnitSpec with GuiceOneAppPerTest with MockitoSugar with ScalaFutures with BeforeAndAfterEach  {

  val stubHost = "localhost"
  val stubPort = sys.env.getOrElse("WIREMOCK_SERVICE_LOCATOR_PORT", "11112").toInt
  val wireMockServer = new WireMockServer(wireMockConfig().port(stubPort))

  override def newAppForTest(testData: TestData): Application = GuiceApplicationBuilder()
    .configure("run.mode" -> "Stub")
    .configure(Map(
      "appName" -> "application-name",
      "appUrl" -> "http://example.com",
      "Test.microservice.services.service-locator.host" -> stubHost,
      "Test.microservice.services.service-locator.port" -> stubPort))
    .in(Mode.Test).build()

  override def beforeEach() {
    wireMockServer.start()
    WireMock.configureFor(stubHost, stubPort)
    stubFor(post(urlMatching("http://localhost:11112/registration")).willReturn(aResponse().withStatus(204)))
  }

  trait Setup extends MicroserviceFilterSupport {
    val documentationController = new Documentation(LazyHttpErrorHandler) {}
    val request = FakeRequest()
  }

  "microservice" should {

    "register itelf to service-locator" in new Setup {
      def regPayloadStringFor(serviceName: String, serviceUrl: String): String =
        Json.toJson(Registration(serviceName, serviceUrl, Some(Map("third-party-api" -> "true")))).toString

      verify(1, postRequestedFor(urlMatching("/registration")).
        withHeader("content-type", equalTo("application/json")).
        withRequestBody(equalTo(regPayloadStringFor("application-name", "http://example.com"))))
    }

    "provide definition endpoint and documentation endpoint for each api" in new Setup {
      def normalizeEndpointName(endpointName: String): String = endpointName.replaceAll(" ", "-")

      def verifyDocumentationPresent(version: String, endpointName: String) {
        withClue(s"Getting documentation version '$version' of endpoint '$endpointName'") {
          val documentationResult = documentationController.documentation(version, endpointName)(request)
          status(documentationResult) shouldBe 200
        }
      }

      val result = documentationController.definition()(request)
      status(result) shouldBe 200

      val jsonResponse = jsonBodyOf(result).futureValue

      val versions: Seq[String] = (jsonResponse \\ "version") map (_.as[String])
      val endpointNames: Seq[Seq[String]] = (jsonResponse \\ "endpoints").map(_ \\ "endpointName").map(_.map(_.as[String]))

      versions.zip(endpointNames).flatMap { case (version, endpoint) => {
        endpoint.map(endpointName => (version, endpointName))
      }
      }.foreach { case (version, endpointName) => verifyDocumentationPresent(version, endpointName) }
    }

    "provide raml documentation" in new Setup {
      val result = documentationController.raml("1.0", "application.raml")(request)

      status(result) shouldBe 200
      bodyOf(result).futureValue should startWith("#%RAML 1.0")
    }
  }

  override protected def afterEach(): Unit = {
    wireMockServer.stop()
    wireMockServer.resetMappings()
  }
}
