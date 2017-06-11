package org.change.v2.abstractnet.neutron.elements

import org.change.v2.abstractnet.neutron.NeutronWrapper
import org.change.v2.analysis.expression.concrete.ConstantValue
import org.change.v2.analysis.processingmodels.Instruction
import org.change.v2.analysis.processingmodels.instructions._
import org.openstack4j.model.network.options.PortListOptions
import org.change.v2.util.canonicalnames._

import scala.collection.JavaConversions._
import org.change.v2.util.conversion.RepresentationConversion._
/**
  * Created by Dragos on 5/25/2017.
  */
class NeutronSubnet(subnetId : String, wrapper : NeutronWrapper) extends BaseNetElement(wrapper) {

  lazy val subnet = wrapper.getOs.networking().subnet().get(subnetId)
  lazy val networkId = subnet.getNetworkId

  lazy val portsForNet = wrapper.getOs.networking().port().list(PortListOptions.create().networkId(networkId))

  override def symnetCode(): Instruction = {
    portsForNet.foldRight(Fail("No ARP entry found") : Instruction)( (x, acc) => {
      if (x.getFixedIps.exists(u => u.getSubnetId == subnetId))
      {
        val allowedStuff = x.getAllowedAddressPairs.foldRight(acc)((s, acc2) => {
          val ip = s.getIpAddress
          val mac = s.getMacAddress
          If (Constrain(IPDst, :==:(ConstantValue(ipToNumber(ip)))),
            InstructionBlock(
              Assign(EtherDst, ConstantValue(macToNumber(mac))),
              Forward(s"Subnet/${subnetId}//out")
            ),
            acc2
          )
        })
        val addressesForMac = x.getFixedIps.filter(p => p.getSubnetId == subnetId).foldRight(allowedStuff)((f, acc3) => {
          val ip =
            if (f.getIpAddress.contains("/"))
              f.getIpAddress.split("/")(0)
            else
              f.getIpAddress
          If (Constrain(IPDst, :==:(ConstantValue(ipToNumber(ip)))),
            InstructionBlock(
              Assign(EtherDst, ConstantValue(macToNumber(x.getMacAddress))),
              Forward(s"Subnet/${subnetId}//out")
            ),
            acc3
          )
        })
        addressesForMac
      }
      else
      {
        acc
      }
    })
  }
}