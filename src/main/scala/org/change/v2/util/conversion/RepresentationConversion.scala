package org.change.v2.util.conversion

object RepresentationConversion {

  def ipToNumber(ip: String): Long = {
    ip.split("\\.").map(Integer.parseInt(_)).foldLeft(0L)((a: Long, g: Int) => a * 256 + g)
  }

  def macToNumber(mac: String): Long = {
    mac.toLowerCase.split(":").map(Integer.parseInt(_, 16)).foldLeft(0L)((a: Long, g: Int) => a * 256 + g)
  }

  /**
    * TODO: Check what is the Cisco format.
    *
    * @param mac
    * @return
    */
  def macToNumberCiscoFormat(mac: String): Long = {
    mac.toLowerCase.split("\\.").map(Integer.parseInt(_, 16)).foldLeft(0L)((a: Long, g: Int) => a * 65536 + g)
  }

  /**
    * Get yourself into CIDR mode
    */
  def ipAndMaskToInterval(masked: String): (Long, Long) = {
    val split = masked.split("/")
    ipAndMaskToInterval(split(0), Integer.parseInt(split(1)))
  }

  def ipAndMaskToInterval(ip: Long, mask: Int): (Long, Long) = {
    val ipv = ip
    val maskv = mask
    val addrS = 32 - maskv
    val lowerM = Long.MaxValue << addrS
    val higherM = Long.MaxValue >>> (maskv + 31)
    (ipv & lowerM, ipv | higherM)
  }

  def ipAndMaskToInterval(ip: String, mask: Int): (Long, Long) = {
    ipAndMaskToInterval(ipToNumber(ip), mask)
  }

  def ipAndMaskToInterval(ip: String, mask: String): (Long, Long) = {
    ipAndMaskToInterval(ipToNumber(ip), Integer.parseInt(mask))
  }

  def ipAndExplicitMaskToInterval(ip: String, mask: String): (Long, Long) = {
    val ipv = ipToNumber(ip)
    val maskv = ipToNumber(mask)
    (ipv & maskv, (((ipv | (~maskv)) << 32) >>> 32))
  }

  def numberToIP(a: Long): String = {
    var s = (a % 256).toString
    var aRest = a >> 8
    for (_ <- 0 until 3) {
      s = (aRest % 256) + "." + s
      aRest = aRest >> 8
    }
    s
  }
}
