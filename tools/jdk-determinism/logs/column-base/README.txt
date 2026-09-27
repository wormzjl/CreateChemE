# column/thermo/material tests (121 files; V3LiteraturePresetTest and V3MethaneColumnTest need Minecraft classes and are left out) compiled with javac against the harness main build of efd123a (out-before), run with the JUnit console launcher under three runtimes by coltest.sh (paths are the session scratchpad's). ColumnInputPresetTest fails on every runtime because the harness stand-in ColumnCalculatorV3BlockEntity has no writeInput; it passes in Gradle.
base-hs21 exit=1  563 tests found , 562 tests successful , 1 tests failed 
base-semeru21 exit=1  563 tests found , 557 tests successful , 6 tests failed 
base-temurin25 exit=1  563 tests found , 559 tests successful , 4 tests failed 
