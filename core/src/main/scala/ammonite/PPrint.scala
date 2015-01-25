
package ammonite
import collection.immutable
import scala.annotation.unchecked.uncheckedVariance
import scala.language.experimental.macros
import scala.reflect.ClassTag
import acyclic.file

/**
 * Prettyprint a strongly-typed value, falling back to toString
 * if you don't know what to do with it. Generally used for human-facing
 * output
 */
object PPrint extends LowPriPPrint{

  def apply[T: PPrint](t: T)(implicit c: PPrint.Config): String = {
    implicitly[PPrint[T]].render(t, c)
  }

  case class Config(maxDepth: Int = 100,
                     depth: Int = 0,
                     color: Boolean = false,
                     renames: Map[String, String] = PPrint.Config.defaultRenames)
    extends GenConfig[Config]{
    def deeper = copy(depth = depth + 1)
    def rename(s: String) = {
      renames.getOrElse(s, s)
    }
  }

  object Config{
    val defaultRenames = Map(
      "WrappedArray" -> "Array"
    ) ++ (2 to 22).map(i => s"Tuple$i" -> "")
    //  println(defaultRenames)
    implicit val default = PPrint.Config()
  }

  implicit def Contra[A](implicit ca: PPrinter[A]): PPrint[A] = PPrint(ca)
}

case class PPrint[A](a: PPrinter[A]){
  def render(t: A, c: PPrint.Config) = {
    if (t == null) "null"
    else a.render(t, c)
  }
  def map(f: String => String) = a.map(f)
}


trait PPrinter[-A] {
  def render(t: A, c: PPrint.Config): String

  def map(f: String => String): (PPrinter[A] @uncheckedVariance) = PPrinter {
    (t: A, c: PPrint.Config) => f(render(t, c))
  }
}

object PPrinter extends PPrinterGen{
  // Things being injected into PPrinterGen to keep it acyclic
  type PPer[T] = PPrinter[T]
  type PP[T] = PPrint[T]
  type Config = PPrint.Config
  def render[T: PP](t: T, c: Config) = implicitly[PPrint[T]].render(t, c)
  def apply[T](r: (T, PPrint.Config) => String): PPrinter[T] = {
    new PPrinter[T]{def render(t: T, c: PPrint.Config)= r(t, c)}
  }


  def defaultColorRepr[T]: PPrinter[T] = PPrinter[T]{
    def colored(color: Boolean, s: String) = {
      if (!color) s
      else Console.GREEN + s + Console.RESET
    }
    (t: T, c: PPrint.Config) => colored(c.color, ""+t)
  }

  implicit val ByteRepr = defaultColorRepr[Byte]
  implicit val ShortRepr = defaultColorRepr[Short]
  implicit val IntRepr = defaultColorRepr[Int]
  implicit val LongRepr = defaultColorRepr[Long].map(_+"L")
  implicit val FloatRepr = defaultColorRepr[Float].map(_+"F")
  implicit val DoubleRepr = defaultColorRepr[Double]
  implicit val CharRepr: PPrinter[Char] = PPrinter.make[Char](x => "'" + escape(x.toString) + "'", Some(Console.GREEN))
  implicit val StringRepr = PPrinter.make[String](x => '"' + escape(x) + '"', Some(Console.GREEN))
  implicit val SymbolRepr = PPrinter.make[Symbol]("'" + _.name, Some(Console.GREEN))

  def make[T](f: T => String, color: Option[String] = None): PPrinter[T] = PPrinter[T]{
    val render = (t: T, c: PPrint.Config) => color.filter(_ => c.color).fold(f(t))(_ + f(t) + Console.RESET)
    def vertical(t: T, c: PPrint.Config) = render(t, c)
    render
  }

  def escape(text: String): String = {
    val s = new StringBuilder
    val len = text.length
    var pos = 0
    var prev = 0

    @inline
    def handle(snip: String) = {
      s.append(text.substring(prev, pos))
      s.append(snip)
    }
    while (pos < len) {
      text.charAt(pos) match {
        case '"' => handle("\\\""); prev = pos + 1
        case '\n' => handle("\\n"); prev = pos + 1
        case '\r' => handle("\\r"); prev = pos + 1
        case '\t' => handle("\\t"); prev = pos + 1
        case '\\' => handle("\\\\"); prev = pos + 1
        case _ =>
      }
      pos += 1
    }
    handle("")
    s.toString()
  }
  implicit def ArrayRepr[T: PPrint] = PPrinter[Array[T]]{
    def repr = collectionRepr[T, Seq[T]]("Array")
    (t: Array[T], c: PPrint.Config) => repr.render(t, c)
  }

  implicit def SeqRepr[T: PPrint] = collectionRepr[T, Seq[T]]("Seq")
  implicit def SetRepr[T: PPrint] = collectionRepr[T, Set[T]]("Set")
  implicit def MapRepr[T: PPrint, V: PPrint]= makeMapRepr[Map, T, V]("Map")

  def makeMapRepr[M[T, V] <: Map[T, V], T: PPrint, V: PPrint](name: String) = PPrinter[M[T, V]] {
    def repr(implicit c: PPrint.Config) = collectionRepr[(T, V), Iterable[(T, V)]](name)(
      PPrint(PPrinter.make[(T, V)]{case (t, v) => PPrint(t) + " -> " + PPrint(v)})
    )
    (t: M[T, V], c: PPrint.Config) => {
      val x = t.toIterable
      repr(c).render(x, c)
    }
  }
  def collectionRepr[T: PPrint, V <: Traversable[T]](name0: String): PPrinter[V] = PPrinter[V] {
    (i: V, c: PPrint.Config) => {
      val chunks = i.map(implicitly[PPrint[T]].render(_, c))
      lazy val chunks2 = i.map(implicitly[PPrint[T]].render(_, c.copy(depth = c.depth + 1)))
      handleChunks(c.renames.getOrElse(i.stringPrefix, i.stringPrefix), chunks, chunks2, c)
    }
  }

  def handleChunks(name0: String,
                   chunks: Traversable[String],
                   chunks2: Traversable[String],
                   c: PPrint.Config): String = {
    val totalLength = name0.length + chunks.map(_.length + 2).sum

    val name =
      if (c.color) Console.YELLOW + name0 + Console.RESET
      else name0

    if (totalLength <= c.maxDepth - c.depth && !chunks.exists(_.contains('\n'))){
      name + "(" + chunks.mkString(", ") + ")"
    } else {

      val indent = "  " * c.depth
      name + "(\n" + chunks2.map("  " + indent + _).mkString(",\n") + "\n" + indent + ")"
    }
  }

  def preMap[T, V: PPrint](f: T => V) = PPrinter[T] {
    (t: T, c: PPrint.Config) => implicitly[PPrint[V]].render(f(t), c)
  }
}



trait LowPriPPrint {
  implicit def FinalRepr[T]: PPrint[T] = macro LowerPriPPrint.FinalRepr[T]
}
object LowerPriPPrint{
  def FinalRepr[T: c.WeakTypeTag](c: scala.reflect.macros.blackbox.Context) = c.Expr[PPrint[T]]{
    import c.universe._

    val tpe = c.weakTypeOf[T]
    util.Try(c.weakTypeOf[T].typeSymbol.asClass) match {
      case util.Success(f) if f.isCaseClass =>

        val constructor = tpe.member(newTermName("<init>"))
        val arity =
          constructor
             .typeSignature
             .paramLists
             .flatten
             .length

        val companion = tpe.typeSymbol.companion
        println(tpe)
        val paramTypes =
          constructor
            .typeSignatureIn(tpe)
            .paramLists
            .flatten
            .map(_.typeSignature)


        import compat._
        val implicits =
          paramTypes map (t =>
            c.inferImplicitValue(
              typeOf[PPrint[Int]] match {
                case TypeRef(pre, tpe, args) =>
                  /*if (t != typeOf[Nothing]) */TypeRef(pre, tpe, List(t))
//                  else TypeRef(pre, tpe, List(typeOf[Int]))
              }
            )
          )
        println(paramTypes)
        val tupleName = newTermName(s"Product${arity}Repr")
        q"""
          new PPrint[$tpe](
            ammonite.PPrinter
                    .preMap((t: $tpe) => $companion.unapply(t).get)(
                      new PPrint(PPrinter.$tupleName[..$paramTypes])
                    )
                    .map(${tpe.typeSymbol.name.toString} + _)
          )
        """

      case _ =>
        q"""new PPrint[$tpe](ammonite.PPrinter.make[$tpe](""+_))"""
    }
  }
}