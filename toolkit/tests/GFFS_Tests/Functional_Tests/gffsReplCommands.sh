#!/bin/bash

#Author: Sal Valente
#mods: Chris Koeritz
#mods: Vanamala Venkataswamy

export WORKDIR="$( \cd "$(\dirname "$0")" && \pwd )"  # obtain the script's working directory.
cd "$WORKDIR"

if [ -z "$GFFS_TOOLKIT_SENTINEL" ]; then echo Please run prepare_tools.sh before testing.; exit 3; fi
source "$GFFS_TOOLKIT_ROOT/library/establish_environment.sh"

function backupEnabled()
{
  if [ -z "$BACKUP_DEPLOYMENT_NAME" -o -z "$BACKUP_USER_DIR" -o -z "$BACKUP_USER_DIR" \
      -o -z "$BACKUP_PORT_NUMBER" ]; then
    return 1  # not enabled.
  else
    return 0  # zero exit is success; is enabled.
  fi
}

oneTimeSetUp()
{
  sanity_test_and_init
}

testReplication()
{
  if ! backupEnabled; then return 0; fi

  grid cd $RNSPATH
  if grid ping dir1 &>/dev/null; then
    grid rm -rf dir1
  fi
  grid mkdir dir1
  assertEquals "grid mkdir" 0 $?
  grid resolver -p dir1 $BACKUP_CONTAINER
  assertEquals "add resolver to directory" 0 $?
  grid replicate -p dir1 $BACKUP_CONTAINER
  assertEquals "replicate directory" 0 $?
  sleep 1

  grid unlink dir2 &>/dev/null
  grid resolver -q dir1 --link=dir2 &> /dev/null
  assertEquals "query resolver" 0 $?

  grid echo "This is file 1" "'>'" dir1/file1.txt
  assertEquals "create first replicated file" 0 $?
  grid echo "File 2 is this" "'>'" dir1/file2.txt
  assertEquals "create second replicated file" 0 $?
  grid echo "Is this file 3" "'>'" dir1/file3.txt
  assertEquals "create third replicated file" 0 $?

  echo showing dir2 after mods on dir1:
  grid ls dir2

  grid rm dir1/file2.txt
  assertEquals "remove replicated file" 0 $?
  grid mkdir dir1/subdirectory
  grid byteio dir1/file1.txt -a "This_was_appended."
  assertEquals "modify replicated file" 0 $?
  grid byteio dir2/file3.txt -a "Appended_to_replica."
  assertEquals "modify replicated file" 0 $?

  grid rm -f ufile.txt &>/dev/null
  grid echo "This is not replicated" "'>'" ufile.txt
  grid ln ufile.txt dir1/ufile-link.txt
  assertEquals "link unreplicated file in replicated dir" 0 $?

  # dir1 must contain 4 entries with the same address as each other:
  # file1.txt, file3.txt, ufile.txt, and subdirectory
  grid ls -m dir1

#echo AA first piece:
#grep '^address: ' $GRID_OUTPUT_FILE 
#echo AA second piece:
#grep '^address: ' $GRID_OUTPUT_FILE | cut -d= -f2 
#echo AA third piece:
#grep '^address: ' $GRID_OUTPUT_FILE | cut -d= -f2 | sort 
#echo AA fourth piece:
#grep '^address: ' $GRID_OUTPUT_FILE | cut -d= -f2 | sort | uniq -c

#old:  count=(`grep '^address: ' $GRID_OUTPUT_FILE | cut -d/ -f3 | sort | uniq -c`)
  # Changed to use container ID by ASG.
  count=(`grep '^address: ' $GRID_OUTPUT_FILE | cut -d= -f2 | sort | uniq -c`)
  assertEquals "replicated directory contents" 4 "${count[0]}"

  # dir2 must contain 3 entries with the same address as each other:
  # file1.txt, file3.txt and subdirectory
  # and it must contain ufile.txt with a different address.
  sync
  grid ls -m dir2

#echo AB first piece:
#grep '^address: ' $GRID_OUTPUT_FILE 
#echo AB second piece:
#grep '^address: ' $GRID_OUTPUT_FILE | cut -d= -f2 
#echo AB third piece:
#grep '^address: ' $GRID_OUTPUT_FILE | cut -d= -f2 | sort 
#echo AB fourth piece:
#grep '^address: ' $GRID_OUTPUT_FILE | cut -d= -f2 | sort | uniq -c
#echo AB fifth piece:
#grep '^address: ' $GRID_OUTPUT_FILE | cut -d= -f2 | sort | uniq -c | sort -nr

#old:  count=( $(grep '^address: ' $GRID_OUTPUT_FILE | cut -d/ -f3 | sort |
#          uniq -c | sort -nr) )
  # Changed to use container ID by ASG.
  count=(`grep '^address: ' $GRID_OUTPUT_FILE | cut -d= -f2 | sort | uniq -c | sort -nr`)
  assertEquals "replica directory content count" 3 "${count[0]}"
  assertEquals "replica directory link count" 1 "${count[2]}"

  # file1 has the correct two lines and file3 has the correct two lines.
  grid cat dir1/file1.txt dir1/file3.txt
  lines=(`wc -l $GRID_OUTPUT_FILE`)
  assertEquals "replicated file contents" 4 "${lines[0]}"

  cp $GRID_OUTPUT_FILE $TEST_TEMP/out$$
  grid cat dir2/file1.txt dir2/file3.txt
  cmp -s $TEST_TEMP/out$$ $GRID_OUTPUT_FILE
  assertEquals "replica file contents" 0 $?

#echo =====================
#echo file 1:
#echo =====================
#cat $TEST_TEMP/out$$ 
#echo =====================
#echo file 2:
#echo =====================
#cat $GRID_OUTPUT_FILE
#echo =====================
}

oneTimeTearDown()
{
  grid rm -rf dir1 &>/dev/null
  grid unlink dir2 &>/dev/null
  grid unlink ufile.txt &>/dev/null
}

# load and run shUnit2
source "$SHUNIT_DIR/shunit2"
