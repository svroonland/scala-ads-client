# scala-ads-client
Beckhoff TwinCAT ADS client for the Scala language

# About

This is a Scala-idiomatic reactive client for [Beckhoff TwinCAT PLC](http://www.beckhoff.com/TwinCAT/). 

## Features
* Get a continuous stream of notifications for PLC variables as ZIO [Streams](https://zio.dev/docs/datatypes/datatypes_stream). 
* Compose and transform Streams to achieve more complex behavior. 
* Automatically closed variable & notification handles via `ZManaged`
* Streaming writing of values to PLC variables
* Easy reading and writing of custom data types (case classes) to PLC `STRUCT`s
* Efficient and typesafe reading and writing of many variables at once using ADS SUM commands
* Fully non-blocking async IO.

Built on top of [zio](https://www.zio.dev) and [zio-nio](https://github.com/zio/zio-nio).

# Documentation

## Connect
The `AdsClient` object provides a `connect` method which will asynchronously connect to an ADS router.

*Important* a route must be added to the ADS router to allow traffic from the source AMS ID.

```scala
import nl.vroste.adsclient._

val settings = AdsConnectionSettings(AmsNetId.fromString("10.211.55.3.1.1"), 801, AmsNetId.fromString("10.211.55.3.10.10"), 123, "localhost")
val client = AdsClient.connect(settings)

client.use { c =>
  // Do stuff with client
}
```

## Reading
To read a PLC variable once:
```scala
import zio._
import zio.clock._
import nl.vroste.adsclient._

val client: AdsClient
val result: ZIO[Clock, AdsClientError, Int] = client.read("MAIN.myIntegerVar", AdsCodecs.int)
```

This will create a variable handle, read using the handle and release the handle.

### Reading many variables at once
To read many variables efficiently and atomically, first create a list of the variable names and their respective codecs and then call the `read()` method.

```scala
import zio._
import zio.clock._
import nl.vroste.adsclient._

val client: AdsClient
val variables = VariableList("MAIN.var1", AdsCodecs.int) + ("MAIN.var2", AdsCodecs.bool)
val result: ZIO[Clock, AdsClientError, Int :: Bool :: HNil] = client.read(variables)

```

The result will be a shapeless `HList`, which you can convert to a tuple using `result.tupled` or to a case class using `Generic[MyCaseClass].from(hlistResult)`.

## Codecs
The `int` parameter to `read()` in the example above is the codec which translates between the PLC datatype and the scala datatype. Because Scala does not have a 1-to-1 corresponding type for all PLC datatypes, the codec has to be provided explicitly. Codecs for unsigned integer types will map PLC values to the `>=0` range of the appropriate Scala data type.

Available codecs are named after the PLC datatype, in the `AdsCodecs` object:

| Codec | PLC data type | Scala data type | Notes |
|-------|---------------|-----------------| ------|
| `bool`  | BOOL          | Boolean         |       |
| `byte`  | BYTE          | Byte            |       |
| `word`  | WORD          | Int             | 16 bit unsigned integer |
| `dword` | DWORD         | Long            | 32 bit unsigned integer |
| `sint`  | SINT          | Int             | 8 bit signed integer       |
| `usint` | USINT         | Int             | 8 bit unsigned integer      |
| `int`   | INT           | Short           | 16 bit signed integer      |
| `uint`  | INT           | Int             | 16 bit unsigned integer       |
| `dint`  | INT           | Int             | 32 bit signed integer       |
| `udint`  | INT           | Long             | 32 bit unsigned integer       |
| `real`  | REAL           | Float             | 32 bit floating point number     |
| `lreal`  | LREAL           | Double             | 64 bit floating point number     |
| `string`  | STRING(80)           | String             | 80 is the default string length     |
| `stringN(maxLength)`  | STRING(maxLength)           | String | String of the given maximum length (maxLength + 1 bytes) |
| `array[T](length, codecForT)` | ARRAY [1..length] OF T | List[T] |
| `date`     | DATE          | LocalDate       | |
| `dateAndTime` | DATE_AND_TIME          | LocalDateTime       | |
| `timeOfDay` | TIME_OF_DAY          | LocalTime       | |
| `time` | TIME          | FiniteDuration       | |

## Notifications
The `AdsClient` can provide notifications for changes to a PLC variable as a `ZStream`. This is the recommended way to repeatedly check a variable for changes without polling.

```scala
import nl.vroste.adsclient._

val client: AdsClient

val notifications: ZStream[Any, Nothing, AdsNotification[String]] = client.notificationsFor("MAIN.myStringVar", AdsCodecs.string)

// Perform further operations on this observable, such as filtering, mapping, joining with observables
// for other PLC variables, etc. 

notifications.subscribe(Consumer.foreach(value => println(s"Got value ${value.value} at timestamp ${value.timestamp}"))
```

Note that the notifications are registered for each `subscribe()` and that they are only started upon subscription. The notification is ended when the subscription stops.

## Writing
The ADS client provides a method for writing to a PLC variable once. This methods returns a `Task` which is completed when the write is complete.
```scala
val writeComplete: Task[Unit] = client.write("MAIN.myUnsignedIntegerVar", uint)
```

### Writing an Observable
When you have an `Observable` of values and you want to write (stream) it to a PLC variable for each emitted value, use the following:
```scala
val strings = Observable.interval(15.seconds)
    .take(10)
    .map(value => s"The next value is ${value}")
    
val consumer = client.consumerFor("MAIN.myStringVar", string)

val done: Task[Unit] = strings.consumeWith(consumer)
```

### Writing many variables at once
To write many variables at once efficiently, create a list of the variables and their respective codecs first. Then construct the value as an `HList` (or convert from a case class or tuple).

```scala
val variableList = VariableList("MAIN.var1", int) + ("MAIN.var2", string) + ("MAIN.var4", int) + ("MAIN.var5", bool)

val values = 3 :: "Dummy" :: 42 :: true
client.write(variableList, values)

```
## Custom datatypes
Codecs can be composed together to map to custom data types such as your own value classes or case classes

### Value classes
A value class can be mapped to a PLC primitive value type. This allows you to write Scala code with more descriptive and possibly restricted types. The automatic conversion is thanks to [Shapeless](https://github.com/milessabin/shapeless).
```scala
case class Degrees(value: Int) extends AnyVal

val degreesCodec: Codec[Degrees] = uint.as
```

### STRUCTs to case classes
Using the same technique, PLC STRUCTs can be mapped to a Scala case class as follows. 

As an example, assume that in the PLC code a `STRUCT` with 3 members is defined:
```
TYPE MyCustomType:
STRUCT
  a: INT := 1;
  b: INT := 1;
  c: BOOL := FALSE;
END_STRUCT
END_TYPE
```

Define the data type and codec on the Scala side as follows:

```scala
case class MyDummyObject(a: Int, b: Int, c: Boolean)

val myDummyObjectCodec: Codec[MyDummyObject] = (int :: int :: bool).as
```
