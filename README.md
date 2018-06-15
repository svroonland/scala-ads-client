# scala-ads-client
Beckhoff TwinCAT ADS client for the Scala language

# About

This is a Scala-native reactive client for [Beckhoff TwinCAT PLC](http://www.beckhoff.com/TwinCAT/). 

## Features
* Fully non-blocking asynchronous IO, powered by Monix [Task](https://monix.io/docs/3x/eval/task.html). 
* Get a continuous stream of notifications for PLC variables as Monix [Observable](https://monix.io/docs/3x/reactive/observable.html)s.
* Writing of elements in an Observable to PLC variables
* Typesafe support for reading and writing of custom data types (case classes) to PLC structs via typeclasses 

Built on top of [monix](https://github.com/monix/monix), [monix-nio](https://github.com/monix/monix-nio) and [cats](https://github.com/typelevel/cats).

# Documentation
TODO

## Connect

## Notifications

## Writing

## Custom datatypes
By declaring an `AdsCodec[T]` for your custom type T, the `AdsClient` can properly serialize and deserialize values of type `T` to the PLC data representation. You can compose a new codec existing ones by using the following syntax.

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

Define the codec on the Scala side as follows:

```scala
import cats.syntax.apply._
import com.vroste.adsclient.DefaultReadables._
import com.vroste.adsclient.DefaultWritables._

case class MyCustomType(a: Int, b: Int, c: Boolean)

implicit val myCustomTypeCodec: AdsCodec[MyCustomType] =
  (AdsCodec.of[Int], AdsCodec.of[Int], AdsCodec.of[Boolean])
    .imapN(MyCustomType.apply)(Function.unlift(MyCustomType.unapply))
```
