/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.juwan.lynx.util

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AppQueryNormalizerTest : FunSpec({
    test("normalizes English app launch instructions to app names") {
        AppQueryNormalizer.normalizeLaunchQuery("Open Calculator app") shouldBe "Calculator"
        AppQueryNormalizer.normalizeLaunchQuery("Please launch the Settings application") shouldBe "Settings"
        AppQueryNormalizer.normalizeLaunchQuery("Open App Store") shouldBe "App Store"
    }

    test("normalizes Chinese launch instructions before downstream task intent") {
        AppQueryNormalizer.normalizeLaunchQuery("打开设置并进入 WLAN 页面") shouldBe "设置"
        AppQueryNormalizer.normalizeLaunchQuery("请打开测试工具给联系人发消息") shouldBe "测试工具"
        AppQueryNormalizer.normalizeLaunchQuery("打开备忘录，新建一条两行草稿") shouldBe "备忘录"
        AppQueryNormalizer.normalizeLaunchQuery("打开日历新建日程") shouldBe "日历"
    }

    test("keeps package names and non-launch queries stable") {
        AppQueryNormalizer.normalizeLaunchQuery("com.android.settings") shouldBe "com.android.settings"
        AppQueryNormalizer.normalizeLaunchQuery("搜索电池") shouldBe "搜索电池"
    }

    test("reports extracted target only when syntax confidently removed task words") {
        AppQueryNormalizer.extractedLaunchTargetOrNull("打开设置并进入 WLAN 页面") shouldBe "设置"
        AppQueryNormalizer.extractedLaunchTargetOrNull("打开备忘录，新建一条两行草稿") shouldBe "备忘录"
        AppQueryNormalizer.extractedLaunchTargetOrNull("Open Calculator app") shouldBe "Calculator"
        AppQueryNormalizer.extractedLaunchTargetOrNull("搜索电池") shouldBe null
    }
})
