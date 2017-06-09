package org.change.v2.model.openflow;

public enum ActionType {
	Output,Enqueue,Normal,Flood,All,Controller,Local,InPort,Drop,ModVlanVid,ModVlanPcp,StripVlan,PushVlan,PushMpls,PopMpls,ModDlSrc,ModDlDst,ModNwSrc,ModNwDst,ModTpSrc,ModTpDst,ModNwTos,Resubmit,SetTunnel,SetTunnel64,SetQueue,PopQueue,DecTtl,SetMplsTtl,DecMplsTtl,Move,Load,Push,Pop,SetField,ApplyActions,ClearActions,WriteMetadata,GotoTable,FinTimeout,Sample,Learn,Exit, CTAction
}
