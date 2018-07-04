//noinspection RedundantNewCaseClass
package millfork.assembly.z80.opt

import millfork.assembly.mos.State
import millfork.assembly.opt._
import millfork.assembly.z80.ZFlag
import millfork.node.ZRegister

/**
  * @author Karol Stasiak
  */

case class CpuStatus(a: Status[Int] = UnknownStatus,
                     b: Status[Int] = UnknownStatus,
                     c: Status[Int] = UnknownStatus,
                     d: Status[Int] = UnknownStatus,
                     e: Status[Int] = UnknownStatus,
                     h: Status[Int] = UnknownStatus,
                     l: Status[Int] = UnknownStatus,
                     ixh: Status[Int] = UnknownStatus,
                     ixl: Status[Int] = UnknownStatus,
                     iyh: Status[Int] = UnknownStatus,
                     iyl: Status[Int] = UnknownStatus,
                     zf: Status[Boolean] = UnknownStatus,
                     nf: Status[Boolean] = UnknownStatus,
                     cf: Status[Boolean] = UnknownStatus,
                     sf: Status[Boolean] = UnknownStatus,
                     pf: Status[Boolean] = UnknownStatus,
                     hf: Status[Boolean] = UnknownStatus
                    ) {
  def setRegister(target: ZRegister.Value, value: Status[Int]): CpuStatus = target match {
    case ZRegister.IMM_8 => this
    case ZRegister.IMM_16 => this
    case ZRegister.A => this.copy(a = value)
    case ZRegister.B => this.copy(b = value)
    case ZRegister.C => this.copy(c = value)
    case ZRegister.D => this.copy(d = value)
    case ZRegister.E => this.copy(e = value)
    case ZRegister.H => this.copy(h = value)
    case ZRegister.L => this.copy(l = value)
    case ZRegister.IXH => this.copy(ixh = value)
    case ZRegister.IXL => this.copy(ixl = value)
    case ZRegister.IYH => this.copy(iyh = value)
    case ZRegister.IYL => this.copy(iyl = value)
    case ZRegister.MEM_IX_D => this
    case ZRegister.MEM_IY_D => this
    case ZRegister.MEM_HL => this
    case ZRegister.MEM_BC => this
    case ZRegister.MEM_DE => this
    case ZRegister.MEM_ABS_8 => this
    case ZRegister.MEM_ABS_16 => this
    case ZRegister.R => this
    case ZRegister.I => this
    case ZRegister.SP => this
    case ZRegister.BC => this.copy(b = value.hi, c = value.lo)
    case ZRegister.DE => this.copy(d = value.hi, e = value.lo)
    case ZRegister.HL => this.copy(h = value.hi, l = value.lo)
    case ZRegister.IX => this.copy(ixh = value.hi, ixl = value.lo)
    case ZRegister.IY => this.copy(iyh = value.hi, iyl = value.lo)
    case ZRegister.AF => this.copy(a = value.hi, cf = AnyStatus, zf = AnyStatus, hf = AnyStatus, pf = AnyStatus, sf = AnyStatus)
  }

  val mergeBytes: (Int, Int) => Int = (h, l) => (h & 0xff) * 256 + (l & 0xff)

  def getRegister(register: ZRegister.Value): Status[Int] = register match {
    case ZRegister.A => a
    case ZRegister.B => b
    case ZRegister.C => c
    case ZRegister.D => d
    case ZRegister.E => e
    case ZRegister.H => h
    case ZRegister.L => l
    case ZRegister.IXH => ixh
    case ZRegister.IXL => ixl
    case ZRegister.IYH => iyh
    case ZRegister.IYL => iyl
    case ZRegister.MEM_IX_D => AnyStatus
    case ZRegister.MEM_IY_D => AnyStatus
    case ZRegister.MEM_HL => AnyStatus
    case ZRegister.MEM_BC => AnyStatus
    case ZRegister.MEM_DE => AnyStatus
    case ZRegister.MEM_ABS_8 => AnyStatus
    case ZRegister.MEM_ABS_16 => AnyStatus
    case ZRegister.R => AnyStatus
    case ZRegister.I => AnyStatus
    case ZRegister.SP => AnyStatus
    case ZRegister.BC => (b <*> c) (mergeBytes)
    case ZRegister.DE => (d <*> e) (mergeBytes)
    case ZRegister.HL => (h <*> l) (mergeBytes)
    case ZRegister.IX => (ixh <*> ixl) (mergeBytes)
    case ZRegister.IY => (iyh <*> iyl) (mergeBytes)
    case ZRegister.AF => AnyStatus
    case ZRegister.IMM_8 => AnyStatus
    case ZRegister.IMM_16 => AnyStatus
  }

  def getFlag(register: ZFlag.Value): Status[Boolean] = register match {
    case ZFlag.C => cf
    case ZFlag.H => hf
    case ZFlag.P => pf
    case ZFlag.Z => zf
    case ZFlag.S => sf
    case ZFlag.N => nf
  }
  def ~(that: CpuStatus) = new CpuStatus(
    a = this.a ~ that.a,
    b = this.b ~ that.b,
    c = this.c ~ that.c,
    d = this.d ~ that.d,
    e = this.e ~ that.e,
    h = this.h ~ that.h,
    l = this.l ~ that.l,
    ixh = this.ixh ~ that.ixh,
    ixl = this.ixl ~ that.ixl,
    iyh = this.iyh ~ that.iyh,
    iyl = this.iyl ~ that.iyl,
    nf = this.nf ~ that.nf,
    sf = this.sf ~ that.sf,
    zf = this.zf ~ that.zf,
    cf = this.cf ~ that.cf,
    pf = this.pf ~ that.pf,
    hf = this.hf ~ that.hf,
  )



  override def toString: String = s"A=$a,B=$b,C=$c,D=$d,E=$e,H=$h,L=$l,IX=$ixh$ixl,Y=$iyh$iyl; Z=$zf,C=$cf,N=$nf,S=$sf,P=$pf,H=$hf"
}