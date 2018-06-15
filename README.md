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

## Connect
The `AdsClient` object provides a `connect` method which will asynchronously connect to 
```scala
val settings = AdsConnectionSettings(...)
val clientT: Task[AdsClient] = AdsClient.connect(settings)

// Example, in a real application you should flatMap the task
clientT.runOnComplete {
 case Success(adsClient) => // Do stuff with the client
 ...
}
```

## Reading
To read a PLC variable once:
```
val client: AdsClient
val result: Task[Int] = client.read[Int]("MAIN.myIntegerVar")
```

## Notifications
Instead of reading once, the recommended way to read is to make use of ADS Notifications of changes to a PLC variable. The `AdsClient` offers these as an `Observable`.

```scala
val notifications: Observable[AdsNotification[Int]] = client.notificationsFor[Int]("MAIN.myIntegerVar")

// Perform further operations on this observable, such as filtering, mapping, joining with observables
// for other PLC variables, etc. 

notifications.subscribe(Consumer.foreach(value => println(s"Got value ${value.value} at timestamp ${value.timestamp}"))
```

Note that the notifications are registered for each `subscribe()` and that they are only created upon subscription. The notification is ended when the subscription stops.

## Writing
The ADS client provides a method for writing to a PLC variable once. This methods returns a task which is completed when the write is complete.
```scala
val writeComplete: Task[Unit] = client.write("MAIN.myIntegerVar", 5)
```

## Writing an Observable
When you have an `Observable` of values and you want to write to a PLC variable for each emitted value, use the following:
```scala
val strings = Observable.interval(15.seconds)
    .take(10)
    .map(value => s"The next value is ${value}")
    
val consumer = client.consumerFor[String]("MAIN.myStringVar")

val done: Task[Unit] = strings.consumeWith(consumer)
```

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
