package dotty.tools
package dotc
package core

import scala.io.Codec
import util.NameTransformer
import printing.{Showable, Texts, Printer}
import Texts.Text
import StdNames.str
import scala.tasty.util.Chars.isIdentifierStart
import collection.immutable
import config.Config
import java.util.HashMap

import scala.annotation.internal.sharable

object Names {
  import NameKinds._

  /** A common class for things that can be turned into names.
   *  Instances are both names and strings, the latter via a decorator.
   */
  trait PreName extends Any with Showable {
    def toTypeName: TypeName
    def toTermName: TermName
  }

  implicit def eqName: Eq[Name, Name] = Eq.derived

  /** A common superclass of Name and Symbol. After bootstrap, this should be
   *  just the type alias Name | Symbol
   */
  abstract class Designator

  /** A name if either a term name or a type name. Term names can be simple
   *  or derived. A simple term name is essentially an interned string stored
   *  in a name table. A derived term name adds a tag, and possibly a number
   *  or a further simple name to some other name.
   */
  abstract class Name extends Designator with PreName {

    /** A type for names of the same kind as this name */
    type ThisName <: Name

    /** Is this name a type name? */
    def isTypeName: Boolean

    /** Is this name a term name? */
    def isTermName: Boolean

    /** This name converted to a type name */
    def toTypeName: TypeName

    /** This name converted to a term name */
    def toTermName: TermName

    /** This name downcasted to a type name */
    def asTypeName: TypeName

    /** This name downcasted to a term name */
    def asTermName: TermName

    /** This name downcasted to a simple term name */
    def asSimpleName: SimpleName

    /** This name converted to a simple term name */
    def toSimpleName: SimpleName

    /** This name converted to a simple term name and in addition
     *  with all symbolic operator characters expanded.
     */
    def mangled: ThisName

    /** Convert to string after mangling */
    def mangledString: String

    /** Apply rewrite rule given by `f` to some part of this name, skipping and rewrapping
     *  other decorators.
     *  Stops at derived names whose kind has `definesNewName = true`.
     *  If `f` does not apply to any part, return name unchanged.
     */
    def replace(f: PartialFunction[Name, Name]): ThisName

    /** If partial function `f` is defined for some part of this name, apply it
     *  in a Some, otherwise None.
     *  Stops at derived names whose kind has `definesNewName = true`.
     */
    def collect[T](f: PartialFunction[Name, T]): Option[T]

    /** Apply `f` to last simple term name making up this name */
    def mapLast(f: SimpleName => SimpleName): ThisName

    /** Apply `f` to all simple term names making up this name */
    def mapParts(f: SimpleName => SimpleName): ThisName

    /** A name in the same (term or type) namespace as this name and
     *  with same characters as given `name`.
     */
    def likeSpaced(name: Name): ThisName

    /** A derived name consisting of this name and the added info, unless it is
     *  already present in this name.
     *  @pre This name does not have a different info of the same kind as `info`.
     */
    def derived(info: NameInfo): ThisName

    /** A derived name consisting of this name and the info of `kind` */
    def derived(kind: ClassifiedNameKind): ThisName = derived(kind.info)

    /** This name without any info of the given `kind`. Excepted, as always,
     *  is the underlying name part of a qualified name.
     */
    def exclude(kind: NameKind): ThisName

    /** Does this name contain an info of the given kind? Excepted, as always,
     *  is the underlying name part of a qualified name.
     */
    def is(kind: NameKind): Boolean

    /** A string showing the internal structure of this name. By contrast, `toString`
     *  shows the name after conversion to a simple name.
     */
    def debugString: String

    /** Convert name to text via `printer`. */
    def toText(printer: Printer): Text = printer.toText(this)

    /** Replace operator expansions by corresponding operator symbols. */
    def decode: ThisName

    /** Replace operator symbols by corresponding operator expansions */
    def encode: ThisName

    /** The first part of this (possible qualified) name */
    def firstPart: SimpleName

    /** The last part of this (possible qualified) name */
    def lastPart: SimpleName

    /** Append `other` to the last part of this name */
    def ++ (other: Name): ThisName = ++ (other.toString)
    def ++ (other: String): ThisName = mapLast(n => termName(n.toString + other))

    /** Replace all occurrences of `from` to `to` in this name */
    def replace(from: Char, to: Char): ThisName = mapParts(_.replace(from, to))

    /** Is this name empty? */
    def isEmpty: Boolean

    /** Does (the first part of) this name start with `str`? */
    def startsWith(str: String): Boolean = firstPart.startsWith(str)

    /** Does (the last part of) this name end with `str`? */
    def endsWith(str: String): Boolean = lastPart.endsWith(str)

    override def hashCode: Int = System.identityHashCode(this)
    override def equals(that: Any): Boolean = this eq that.asInstanceOf[AnyRef]
  }

  /** Names for terms, can be simple or derived */
  abstract class TermName extends Name {
    type ThisName = TermName

    override def isTypeName: Boolean = false
    override def isTermName: Boolean = true
    override def toTermName: TermName = this
    override def asTypeName: Nothing = throw new ClassCastException(this + " is not a type name")
    override def asTermName: TermName = this

    @sharable // because it is only modified in the synchronized block of toTypeName.
    @volatile private[this] var _typeName: TypeName = null

    override def toTypeName: TypeName = {
      if (_typeName == null)
        synchronized {
          if (_typeName == null)
            _typeName = new TypeName(this)
        }
      _typeName
    }

    override def likeSpaced(name: Name): TermName = name.toTermName

    def info: NameInfo = SimpleNameKind.info
    def underlying: TermName = unsupported("underlying")

    @sharable // because of synchronized block in `and`
    private[this] var derivedNames: AnyRef /* immutable.Map[NameInfo, DerivedName] | j.u.HashMap */ =
      immutable.Map.empty[NameInfo, DerivedName]

    private def getDerived(info: NameInfo): DerivedName /* | Null */ = derivedNames match {
      case derivedNames: immutable.AbstractMap[NameInfo, DerivedName] @unchecked =>
        if (derivedNames.contains(info)) derivedNames(info) else null
      case derivedNames: HashMap[NameInfo, DerivedName] @unchecked =>
        derivedNames.get(info)
    }

    private def putDerived(info: NameInfo, name: DerivedName): name.type = {
      derivedNames match {
        case derivedNames: immutable.Map[NameInfo, DerivedName] @unchecked =>
          if (derivedNames.size < 4)
            this.derivedNames = derivedNames.updated(info, name)
          else {
            val newMap = new HashMap[NameInfo, DerivedName]
            derivedNames.foreach { case (k, v) => newMap.put(k, v) }
            newMap.put(info, name)
            this.derivedNames = newMap
          }
        case derivedNames: HashMap[NameInfo, DerivedName] @unchecked =>
          derivedNames.put(info, name)
      }
      name
    }

    private def add(info: NameInfo): TermName = synchronized {
      getDerived(info) match {
        case null        => putDerived(info, new DerivedName(this, info))
        case derivedName => derivedName
      }
    }

    private def rewrap(underlying: TermName) =
      if (underlying eq this.underlying) this else underlying.add(info)

    override def derived(info: NameInfo): TermName = {
      val thisKind = this.info.kind
      val thatKind = info.kind
      if (thisKind.tag < thatKind.tag || thatKind.definesNewName) add(info)
      else if (thisKind.tag > thatKind.tag) rewrap(underlying.derived(info))
      else {
        assert(info == this.info)
        this
      }
    }

    /** Is it impossible that names of kind `kind` also qualify as names of kind `shadowed`? */
    private def shadows(kind: NameKind, shadowed: NameKind): Boolean =
      kind.tag < shadowed.tag ||
      kind.definesQualifiedName ||
      kind.definesNewName && !shadowed.definesQualifiedName

    override def exclude(kind: NameKind): TermName = {
      val thisKind = this.info.kind
      if (shadows(thisKind, kind)) this
      else if (thisKind.tag > kind.tag) rewrap(underlying.exclude(kind))
      else underlying
    }

    override def is(kind: NameKind): Boolean = {
      val thisKind = this.info.kind
      thisKind == kind ||
      !shadows(thisKind, kind) && underlying.is(kind)
    }

    @sharable // because it's just a cache for performance
    private[this] var myMangledString: String = null

    @sharable // because it's just a cache for performance
    private[this] var myMangled: Name = null

    protected[Names] def mangle: ThisName

    final def mangled: ThisName = {
      if (myMangled == null) myMangled = mangle
      myMangled.asInstanceOf[ThisName]
    }

    final def mangledString: String = {
      if (myMangledString == null)
        myMangledString = qualToString(_.mangledString, _.mangled.toString)
      myMangledString
    }

    /** If this a qualified name, split it into underlyng, last part, and separator
     *  Otherwise return an empty name, the name itself, and "")
     */
    def split: (TermName, TermName, String)

    /** Convert to string as follows. If this is a qualified name
     *  `<first> <sep> <last>`, the sanitized version of `f1(<first>) <sep> f2(<last>)`.
     *  Otherwise `f2` applied to this name.
     */
    def qualToString(f1: TermName => String, f2: TermName => String): String = {
      val (first, last, sep) = split
      if (first.isEmpty) f2(last) else str.sanitize(f1(first) + sep + f2(last))
    }
  }

  /** A simple name is essentially an interned string */
  final class SimpleName(val start: Int, val length: Int, @sharable private[Names] var next: SimpleName) extends TermName {
    // `next` is @sharable because it is only modified in the synchronized block of termName.

    /** The n'th character */
    def apply(n: Int): Char = chrs(start + n)

    /** A character in this name satisfies predicate `p` */
    def exists(p: Char => Boolean): Boolean = {
      var i = 0
      while (i < length && !p(chrs(start + i))) i += 1
      i < length
    }

    /** All characters in this name satisfy predicate `p` */
    def forall(p: Char => Boolean): Boolean = !exists(!p(_))

    /** The name contains given character `ch` */
    def contains(ch: Char): Boolean = {
      var i = 0
      while (i < length && chrs(start + i) != ch) i += 1
      i < length
    }

    /** The index of the last occurrence of `ch` in this name which is at most
     *  `start`.
     */
    def lastIndexOf(ch: Char, start: Int = length - 1): Int = {
      var i = start
      while (i >= 0 && apply(i) != ch) i -= 1
      i
    }

    /** The index of the last occurrence of `str` in this name */
    def lastIndexOfSlice(str: String): Int = toString.lastIndexOfSlice(str)

    /** A slice of this name making up the characters between `from` and `until` (exclusive) */
    def slice(from: Int, end: Int): SimpleName = {
      assert(0 <= from && from <= end && end <= length)
      termName(chrs, start + from, end - from)
    }

    def drop(n: Int): SimpleName = slice(n, length)
    def take(n: Int): SimpleName = slice(0, n)
    def dropRight(n: Int): SimpleName = slice(0, length - n)
    def takeRight(n: Int): SimpleName = slice(length - n, length)

    /** Same as slice, but as a string */
    def sliceToString(from: Int, end: Int): String =
      if (end <= from) "" else new String(chrs, start + from, end - from)

    def head: Char = apply(0)
    def last: Char = apply(length - 1)

    override def asSimpleName: SimpleName = this
    override def toSimpleName: SimpleName = this
    override final def mangle: SimpleName = encode

    override def replace(f: PartialFunction[Name, Name]): ThisName =
      if (f.isDefinedAt(this)) likeSpaced(f(this)) else this
    override def collect[T](f: PartialFunction[Name, T]): Option[T] = f.lift(this)
    override def mapLast(f: SimpleName => SimpleName): SimpleName = f(this)
    override def mapParts(f: SimpleName => SimpleName): SimpleName = f(this)
    override def split: (TermName, SimpleName, String) = (EmptyTermName, this, "")

    override def encode: SimpleName = {
      val dontEncode =
        length >= 3 &&
        head == '<' && last == '>' && isIdentifierStart(apply(1))
      if (dontEncode) this else NameTransformer.encode(this)
    }

    override def decode: SimpleName = NameTransformer.decode(this)

    override def isEmpty: Boolean = length == 0

    override def startsWith(str: String): Boolean = {
      var i = 0
      while (i < str.length && i < length && apply(i) == str(i)) i += 1
      i == str.length
    }

    override def endsWith(str: String): Boolean = {
      var i = 1
      while (i <= str.length && i <= length && apply(length - i) == str(str.length - i)) i += 1
      i > str.length
    }

    override def replace(from: Char, to: Char): SimpleName = {
      val cs = new Array[Char](length)
      System.arraycopy(chrs, start, cs, 0, length)
      for (i <- 0 until length) {
        if (cs(i) == from) cs(i) = to
      }
      termName(cs, 0, length)
    }

    override def firstPart: SimpleName = this
    override def lastPart: SimpleName = this

    override def hashCode: Int = start

    override def toString: String =
      if (length == 0) ""
      else {
        if (Config.checkBackendNames) {
          if (!toStringOK) {
            // We print the stacktrace instead of doing an assert directly,
            // because asserts are caught in exception handlers which might
            // cause other failures. In that case the first, important failure
            // is lost.
            System.err.println("Backend should not call Name#toString, Name#mangledString should be used instead.")
            Thread.dumpStack()
            assert(false)
          }
        }
        new String(chrs, start, length)
      }

    /** It's OK to take a toString if the stacktrace does not contain a method
     *  from GenBCode or it also contains one of the whitelisted methods below.
     */
    private def toStringOK = {
      val trace = Thread.currentThread.getStackTrace
      !trace.exists(_.getClassName.endsWith("GenBCode")) ||
      trace.exists(elem =>
          List(
              "mangledString",
              "toSimpleName",
              "decode",
              "unmangle",
              "dotty$tools$dotc$core$NameOps$NameDecorator$$functionArityFor$extension",
              "dotty$tools$dotc$typer$Checking$CheckNonCyclicMap$$apply",
              "$plus$plus",
              "readConstant",
              "extractedName")
            .contains(elem.getMethodName))
    }

    def debugString: String = toString
  }

  final class TypeName(val toTermName: TermName) extends Name {

    type ThisName = TypeName

    override def isTypeName: Boolean = true
    override def isTermName: Boolean = false
    override def toTypeName: TypeName = this
    override def asTypeName: TypeName = this
    override def asTermName: Nothing = throw new ClassCastException(this + " is not a term name")

    override def asSimpleName: SimpleName = toTermName.asSimpleName
    override def toSimpleName: SimpleName = toTermName.toSimpleName
    override def mangled: TypeName = toTermName.mangled.toTypeName
    override def mangledString: String = toTermName.mangledString

    override def replace(f: PartialFunction[Name, Name]): ThisName = toTermName.replace(f).toTypeName
    override def collect[T](f: PartialFunction[Name, T]): Option[T] = toTermName.collect(f)
    override def mapLast(f: SimpleName => SimpleName): TypeName = toTermName.mapLast(f).toTypeName
    override def mapParts(f: SimpleName => SimpleName): TypeName = toTermName.mapParts(f).toTypeName

    override def likeSpaced(name: Name): TypeName = name.toTypeName

    override def derived(info: NameInfo): TypeName = toTermName.derived(info).toTypeName
    override def exclude(kind: NameKind): TypeName = toTermName.exclude(kind).toTypeName
    override def is(kind: NameKind): Boolean = toTermName.is(kind)

    override def isEmpty: Boolean = toTermName.isEmpty

    override def encode: TypeName   = toTermName.encode.toTypeName
    override def decode: TypeName   = toTermName.decode.toTypeName
    override def firstPart: SimpleName = toTermName.firstPart
    override def lastPart: SimpleName = toTermName.lastPart

    override def toString: String = toTermName.toString
    override def debugString: String = toTermName.debugString + "/T"
  }

  /** A term name that's derived from an `underlying` name and that
   *  adds `info` to it.
   */
  final case class DerivedName(override val underlying: TermName, override val info: NameInfo)
  extends TermName {

    override def asSimpleName: Nothing = throw new UnsupportedOperationException(s"$debugString is not a simple name")

    override def toSimpleName: SimpleName = termName(toString)
    override final def mangle: SimpleName = encode.toSimpleName

    override def replace(f: PartialFunction[Name, Name]): ThisName =
      if (f.isDefinedAt(this)) likeSpaced(f(this))
      else info match {
        case qual: QualifiedInfo => this
        case _ => underlying.replace(f).derived(info)
      }

    override def collect[T](f: PartialFunction[Name, T]): Option[T] =
      if (f.isDefinedAt(this)) Some(f(this))
      else info match {
        case qual: QualifiedInfo => None
        case _ => underlying.collect(f)
      }

    override def mapLast(f: SimpleName => SimpleName): ThisName =
      info match {
        case qual: QualifiedInfo => underlying.derived(qual.map(f))
        case _ => underlying.mapLast(f).derived(info)
      }

    override def mapParts(f: SimpleName => SimpleName): ThisName =
      info match {
        case qual: QualifiedInfo => underlying.mapParts(f).derived(qual.map(f))
        case _ => underlying.mapParts(f).derived(info)
      }

    override def split: (TermName, TermName, String) = info match {
      case info: QualifiedInfo =>
        (underlying, info.name, info.kind.asInstanceOf[QualifiedNameKind].separator)
      case _ =>
        val (prefix, suffix, separator) = underlying.split
        (prefix, suffix.derived(info), separator)
    }

    override def isEmpty: Boolean = false
    override def encode: ThisName = underlying.encode.derived(info.map(_.encode))
    override def decode: ThisName = underlying.decode.derived(info.map(_.decode))
    override def firstPart: SimpleName = underlying.firstPart
    override def lastPart: SimpleName = info match {
      case qual: QualifiedInfo => qual.name
      case _ => underlying.lastPart
    }
    override def toString: String = info.mkString(underlying)
    override def debugString: String = s"${underlying.debugString}[$info]"
  }

  // Nametable

  private final val InitialHashSize = 0x8000
  private final val InitialNameSize = 0x20000
  private final val fillFactor = 0.7

  /** Memory to store all names sequentially. */
  @sharable // because it's only mutated in synchronized block of termName
  private[dotty] var chrs: Array[Char] = new Array[Char](InitialNameSize)

  /** The number of characters filled. */
  @sharable // because it's only mutated in synchronized block of termName
  private[this] var nc = 0

  /** Hashtable for finding term names quickly. */
  @sharable // because it's only mutated in synchronized block of termName
  private[this] var table = new Array[SimpleName](InitialHashSize)

  /** The number of defined names. */
  @sharable // because it's only mutated in synchronized block of termName
  private[this] var size = 1

  /** The hash of a name made of from characters cs[offset..offset+len-1].  */
  private def hashValue(cs: Array[Char], offset: Int, len: Int): Int = {
    var i = offset
    var hash = 0
    while (i < len + offset) {
      hash = 31 * hash + cs(i)
      i += 1
    }
    hash
  }

  /** Is (the ASCII representation of) name at given index equal to
   *  cs[offset..offset+len-1]?
   */
  private def equals(index: Int, cs: Array[Char], offset: Int, len: Int): Boolean = {
    var i = 0
    while ((i < len) && (chrs(index + i) == cs(offset + i)))
      i += 1
    i == len
  }

  /** Create a term name from the characters in cs[offset..offset+len-1].
   *  Assume they are already encoded.
   */
  def termName(cs: Array[Char], offset: Int, len: Int): SimpleName = synchronized {
    util.Stats.record("termName")
    val h = hashValue(cs, offset, len) & (table.length - 1)

    /** Make sure the capacity of the character array is at least `n` */
    def ensureCapacity(n: Int) =
      if (n > chrs.length) {
        val newchrs = new Array[Char](chrs.length * 2)
        chrs.copyToArray(newchrs)
        chrs = newchrs
      }

    /** Enter characters into chrs array. */
    def enterChars(): Unit = {
      ensureCapacity(nc + len)
      var i = 0
      while (i < len) {
        chrs(nc + i) = cs(offset + i)
        i += 1
      }
      nc += len
    }

    /** Rehash chain of names */
    def rehash(name: SimpleName): Unit =
      if (name != null) {
        val oldNext = name.next
        val h = hashValue(chrs, name.start, name.length) & (table.size - 1)
        name.next = table(h)
        table(h) = name
        rehash(oldNext)
      }

    /** Make sure the hash table is large enough for the given load factor */
    def incTableSize() = {
      size += 1
      if (size.toDouble / table.size > fillFactor) {
        val oldTable = table
        table = new Array[SimpleName](table.size * 2)
        for (i <- 0 until oldTable.size) rehash(oldTable(i))
      }
    }

    val next = table(h)
    var name = next
    while (name ne null) {
      if (name.length == len && equals(name.start, cs, offset, len))
        return name
      name = name.next
    }
    name = new SimpleName(nc, len, next)
    enterChars()
    table(h) = name
    incTableSize()
    name
  }

  /** Create a type name from the characters in cs[offset..offset+len-1].
   *  Assume they are already encoded.
   */
  def typeName(cs: Array[Char], offset: Int, len: Int): TypeName =
    termName(cs, offset, len).toTypeName

  /** Create a term name from the UTF8 encoded bytes in bs[offset..offset+len-1].
   *  Assume they are already encoded.
   */
  def termName(bs: Array[Byte], offset: Int, len: Int): SimpleName = {
    val chars = Codec.fromUTF8(bs, offset, len)
    termName(chars, 0, chars.length)
  }

  /** Create a type name from the UTF8 encoded bytes in bs[offset..offset+len-1].
   *  Assume they are already encoded.
   */
  def typeName(bs: Array[Byte], offset: Int, len: Int): TypeName =
    termName(bs, offset, len).toTypeName

  /** Create a term name from a string, without encoding operators */
  def termName(s: String): SimpleName = termName(s.toCharArray, 0, s.length)

  /** Create a type name from a string, without encoding operators */
  def typeName(s: String): TypeName = typeName(s.toCharArray, 0, s.length)

  table(0) = new SimpleName(-1, 0, null)

  /** The term name represented by the empty string */
  val EmptyTermName: TermName = table(0)

  /** The type name represented by the empty string */
  val EmptyTypeName: TypeName = EmptyTermName.toTypeName

  implicit val NameOrdering: Ordering[Name] = new Ordering[Name] {
    private def compareInfos(x: NameInfo, y: NameInfo): Int =
      if (x.kind.tag != y.kind.tag) x.kind.tag - y.kind.tag
      else x match {
        case x: QualifiedInfo =>
          y match {
            case y: QualifiedInfo =>
              compareSimpleNames(x.name, y.name)
          }
        case x: NumberedInfo =>
          y match {
            case y: NumberedInfo =>
              x.num - y.num
          }
        case _ =>
          assert(x == y)
          0
      }
    private def compareSimpleNames(x: SimpleName, y: SimpleName): Int = {
      val until = x.length min y.length
      var i = 0
      while (i < until && x(i) == y(i)) i = i + 1
      if (i < until) {
        if (x(i) < y(i)) -1
        else /*(x(i) > y(i))*/ 1
      } else {
        x.length - y.length
      }
    }
    private def compareTermNames(x: TermName, y: TermName): Int = x match {
      case x: SimpleName =>
        y match {
          case y: SimpleName => compareSimpleNames(x, y)
          case _ => -1
        }
      case DerivedName(xPre, xInfo) =>
        y match {
          case DerivedName(yPre, yInfo) =>
            val s = compareInfos(xInfo, yInfo)
            if (s == 0) compareTermNames(xPre, yPre) else s
          case _ => 1
        }
    }
    def compare(x: Name, y: Name): Int = {
      if (x.isTermName && y.isTypeName) 1
      else if (x.isTypeName && y.isTermName) -1
      else if (x eq y) 0
      else compareTermNames(x.toTermName, y.toTermName)
    }
  }
}
