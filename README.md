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
