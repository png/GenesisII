#!/bin/sh

if [ $# -lt 1 ]
then
	echo "USAGE:  shim.sh <program-to-shim> [args]"
	exit 1
fi

JSERVER_LOCATION="%{OGRSH_HOME}/.."

TMP_FILENAME=/tmp/$USER.shim.$RANDOM
while [ -e $TMP_FILENAME ]
do
	TMP_FILENAME=/tmp/$USER.shim.$RANDOM
done

cd "$JSERVER_LOCATION"
"./jserver-debug.sh" > $TMP_FILENAME &
JSERVER_PID=$!

while [ ! -e $TMP_FILE ]
do
	sleep 1
done

LINES=0
while [[ $LINES -lt 2 ]]
do
	LINES=`wc -l $TMP_FILENAME | sed -e "s/ .*//" 2> /dev/null`
done
export LINES=

LINE=`head -2 $TMP_FILENAME | tail -1`
export OGRSH_JSERVER_ADDRESS="127.0.0.1"
export OGRSH_JSERVER_SECRET=`echo $LINE | sed -e 's/^Server.//' | sed -e 's/].*//'`
export OGRSH_JSERVER_PORT=`echo $LINE | sed -e 's/^.*port //'`
export LINE=
export HOME="/home/mmm2a"
export OGRSH_CONFIG="%{OGRSH_HOME}/config/example.xml"
export LD_LIBRARY_PATH="%{OGRSH_HOME}/lib/%{OGRSH_ARCH}:$LD_LIBRARY_PATH"
export LD_PRELOAD=libOGRSH.so

PROGRAM="$1"
shift
"$PROGRAM" "$@"

export LD_PRELOAD=
export OGRSH_JSERVER_SECRET=

kill $JSERVER_PID
/bin/rm -f -r $TMP_FILENAME
