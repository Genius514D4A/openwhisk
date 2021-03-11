#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
#
# Python based OpenWhisk action that sleeps for the specified number
# of milliseconds before returning.
# The function actually sleeps slightly longer than requested.
#
# @param parm Object with Number property sleepTimeInMs
# @returns Object with String property msg describing how long the function slept
#
import time


def main(parm):
    sleepTimeInMs = parm.get("sleepTimeInMs", 1)
    print("Specified sleep time is {} ms.".format(sleepTimeInMs))

    result = {
        "msg": "Terminated successfully after around {} ms.".format(sleepTimeInMs)
    }

    time.sleep(sleepTimeInMs / 1000.0)

    print(result["msg"])
    return result
