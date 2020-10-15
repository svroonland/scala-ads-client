package nl.vroste.adsclient
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.must.Matchers

trait BaseSpec extends AsyncFlatSpec with Matchers with WithAdsClient with MonixSupport {}
