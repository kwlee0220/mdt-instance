#! /bin/bash

docker image rmi kwlee0220/mdt-instance

cp ../build/libs/mdt-instance-1.0.0-all.jar mdt-instance-all.jar

docker build --build-arg LOCAL_HOST=$LOCAL_HOST -t kwlee0220/mdt-instance:latest .

rm mdt-instance-all.jar
