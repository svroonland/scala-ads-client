package nl.vroste.adsclient

import org.scalatest.{AsyncFlatSpec, MustMatchers}

trait BaseSpec extends AsyncFlatSpec with MustMatchers with WithAdsClient with MonixSupport {

}
