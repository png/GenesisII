#!/bin/bash

#Author: Vanamala Venkataswamy
#mods: Chris Koeritz

export WORKDIR="$( \cd "$(\dirname "$0")" && \pwd )"  # obtain the script's working directory.
cd "$WORKDIR"

source "../../../prepare_tools.sh" "../../../prepare_tools.sh"
if [ -z "$TEST_TEMP" ]; then
  echo The xsede tool suite could not be automatically located.
  exit 1
fi

if [ -z "$GFFS_TOOLKIT_SENTINEL" ]; then echo Please run prepare_tools.sh before testing.; exit 3; fi
source "$GFFS_TOOLKIT_ROOT/library/establish_environment.sh"

oneTimeSetUp()
{
  sanity_test_and_init  # make sure test environment is good.

  create_jsdl_from_templates  # turn template files into the real jsdl.
  if [ $? -ne 0 ]; then
    echo "JSDL file generation failure.  unassigned random id perhaps?"
    exit 1
  fi

  echo "Copying necessary files to Grid namespace"
  grid cp local:./cat.sh grid:$RNSPATH
  grid cp local:./hostname.sh grid:$RNSPATH
  grid cp local:./hostname-sleep.sh grid:$RNSPATH

}

testQueueSubmitSimple()
{
  grid qsub $QUEUE_PATH local:$GENERATED_JSDL_FOLDER/ls-single-job.jsdl
  assertEquals "Submitting single '/bin/ls' job" 0 $?

  grid qsub $QUEUE_PATH local:$GENERATED_JSDL_FOLDER/hostname-sleep-60s.jsdl
  assertEquals "Submitting single '/bin/hostname'; sleep 60 job" 0 $?

  grid qsub $QUEUE_PATH local:$GENERATED_JSDL_FOLDER/hostname-stage-out.jsdl
  assertEquals "Submitting '/bin/hostname' job, sleep for 60s, stage-out the output.txt" 0 $?

  grid qsub $QUEUE_PATH local:$GENERATED_JSDL_FOLDER/cat-stdin-stageout.jsdl
  assertEquals "Submitting single 'cat' job to cat a file, stage-out the output file" 0 $?
}

testWaitForSimpleJobs()
{
	grid qstat $QUEUE_PATH
  wait_for_all_pending_jobs $QUEUE_PATH whack
  assertEquals "No simple jobs should be left" 0 $?
}

testQueueSubmitManyWithSleep()
{
# 2020-03-30 by ASG
echo "Skipping test - no http, scp, filestaging hosts"
return 0

  grid qsub $QUEUE_PATH local:$GENERATED_JSDL_FOLDER/hostname-parameter-sweep-100.jsdl
  assertEquals "Submitting 100 '/bin/hostname'; sleep 60 jobs" 0 $?
}

testWaitForSleepers()
{
  wait_for_all_pending_jobs $QUEUE_PATH whack
  assertEquals "No sleeping jobs should be left" 0 $?
}


# load and run shUnit2
source "$SHUNIT_DIR/shunit2"

