0.5 
 - Fix a bug in FILO order init/shutdown when objects are singleton
 - Add toProvider/toSingletonProvider/toEagerSingletonProvider
 - Add Design.remove[X]

0.4 
 - Improved binding performance
 - Fix FIFO lifecycle hook executor
 - Improved injection logging

0.3 
 - Support bind(ObjectType).toXXX 

0.2
 - Add lifecycle manageer
 - Reorganize Session, Design classes
 - Test coverage improvement
 - Depreacted Design.build[X]. Use Design.newSession.build[X]

0.1
 - Migrated from wvlet-inject