package millfork.compiler

/**
  * @author Karol Stasiak
  */
object ComparisonType extends Enumeration {
  val Equal, NotEqual,
  LessUnsigned, LessSigned,
  GreaterUnsigned, GreaterSigned,
  LessOrEqualUnsigned, LessOrEqualSigned,
  GreaterOrEqualUnsigned, GreaterOrEqualSigned = Value

  def flip(x: ComparisonType.Value): ComparisonType.Value = x match {
    case LessUnsigned => GreaterUnsigned
    case GreaterUnsigned => LessUnsigned
    case LessOrEqualUnsigned => GreaterOrEqualUnsigned
    case GreaterOrEqualUnsigned => LessOrEqualUnsigned
    case LessSigned => GreaterSigned
    case GreaterSigned => LessSigned
    case LessOrEqualSigned => GreaterOrEqualSigned
    case GreaterOrEqualSigned => LessOrEqualSigned
    case _ => x
  }

  def negate(x: ComparisonType.Value): ComparisonType.Value = x match {
    case LessUnsigned => GreaterOrEqualUnsigned
    case GreaterUnsigned => LessOrEqualUnsigned
    case LessOrEqualUnsigned => GreaterUnsigned
    case GreaterOrEqualUnsigned => LessUnsigned
    case LessSigned => GreaterOrEqualSigned
    case GreaterSigned => LessOrEqualSigned
    case LessOrEqualSigned => GreaterSigned
    case GreaterOrEqualSigned => LessSigned
    case Equal => NotEqual
    case NotEqual => Equal
  }

  def toUnsigned(x: ComparisonType.Value): ComparisonType.Value = x match {
    case LessSigned => LessUnsigned
    case GreaterSigned => GreaterUnsigned
    case LessOrEqualSigned => LessOrEqualUnsigned
    case GreaterOrEqualSigned => GreaterOrEqualUnsigned
    case x => x
  }

  def isSigned(x: ComparisonType.Value): Boolean = x match {
    case LessSigned => true
    case GreaterSigned => true
    case LessOrEqualSigned => true
    case GreaterOrEqualSigned => true
    case _ => false
  }
}
