package scalapb.spark

import com.google.protobuf.ByteString
import frameless.{TypedEncoder, TypedExpressionEncoder}
import org.apache.spark.sql.Encoder
import org.apache.spark.sql.catalyst.expressions.objects.{Invoke, StaticInvoke}
import org.apache.spark.sql.catalyst.expressions.{
  Expression,
  If,
  IsNull,
  Literal
}
import org.apache.spark.sql.types._
import scalapb._
import scalapb.descriptors.{PValue, Reads}

import scala.reflect.ClassTag

class MessageTypedEncoder[T <: GeneratedMessage with Message[T]](
    implicit cmp: GeneratedMessageCompanion[T],
    ct: ClassTag[T]
) extends TypedEncoder[T] {
  override def nullable: Boolean = false

  override def jvmRepr: DataType = ObjectType(ct.runtimeClass)

  override def catalystRepr: DataType = ProtoSQL.schemaFor(cmp)

  def fromCatalyst(path: Expression): Expression = {
    val expr = FromCatalystHelpers.pmessageFromCatalyst(cmp, path)

    val pmsg =
      StaticInvoke(
        JavaHelpers.getClass,
        ObjectType(classOf[PValue]),
        "asPValue",
        expr :: Nil
      )

    val reads = Invoke(
      Literal.fromObject(cmp),
      "messageReads",
      ObjectType(classOf[Reads[_]]),
      Nil
    )

    val read = Invoke(reads, "read", ObjectType(classOf[Function[_, _]]))

    val out = Invoke(read, "apply", ObjectType(ct.runtimeClass), pmsg :: Nil)

    If(IsNull(path), Literal.create(null, out.dataType), out)
  }

  override def toCatalyst(path: Expression): Expression = {
    ToCatalystHelpers.messageToCatalyst(cmp, path)
  }
}

class EnumTypedEncoder[T <: GeneratedEnum](
    implicit cmp: GeneratedEnumCompanion[T],
    ct: ClassTag[T]
) extends TypedEncoder[T] {
  override def nullable: Boolean = false

  override def jvmRepr: DataType = ObjectType(ct.runtimeClass)

  override def catalystRepr: DataType = StringType

  override def fromCatalyst(path: Expression): Expression = {
    val expr = Invoke(
      Literal.fromObject(cmp),
      "fromValue",
      ObjectType(ct.runtimeClass),
      StaticInvoke(
        JavaHelpers.getClass,
        IntegerType,
        "enumValueFromString",
        Literal.fromObject(cmp) :: path :: Nil
      ) :: Nil
    )
    If(IsNull(path), Literal.create(null, expr.dataType), expr)
  }

  override def toCatalyst(path: Expression): Expression =
    StaticInvoke(
      JavaHelpers.getClass,
      StringType,
      "enumToString",
      Literal.fromObject(cmp) :: path :: Nil
    )
}

class ByteStringTypedEncoder extends TypedEncoder[ByteString] {
  override def nullable: Boolean = false

  override def jvmRepr: DataType = ObjectType(classOf[ByteString])

  override def catalystRepr: DataType = BinaryType

  override def fromCatalyst(path: Expression): Expression = StaticInvoke(
    classOf[ByteString],
    ObjectType(classOf[ByteString]),
    "copyFrom",
    path :: Nil
  )

  override def toCatalyst(path: Expression): Expression =
    Invoke(path, "toByteArray", BinaryType, Seq.empty)
}

trait Implicits {
  implicit def messageTypedEncoder[T <: GeneratedMessage with Message[T]: GeneratedMessageCompanion: ClassTag]
      : TypedEncoder[T] = new MessageTypedEncoder[T]

  implicit def enumTypedEncoder[T <: GeneratedEnum](
      implicit cmp: GeneratedEnumCompanion[T],
      ct: ClassTag[T]
  ): TypedEncoder[T] = new EnumTypedEncoder[T]

  implicit val byteStringTypedEncoder = new ByteStringTypedEncoder

  implicit def typedEncoderToEncoder[T: ClassTag](
      implicit ev: TypedEncoder[T]
  ): Encoder[T] =
    TypedExpressionEncoder(ev)
}

object Implicits extends Implicits