package org.enderlink

class Solution {
    fun twoSum(nums: IntArray, target: Int): IntArray {
        val map = hashMapOf<Int, Int>()
        for (i in nums.indices) {
            val need = target - nums[i]
            val prev = map[need]
            if (prev != null) {
                return intArrayOf(i, map[need] ?: -1)
            }
            map[nums[i]] = i
        }
        return intArrayOf()
    }

    fun isPalindrome(x: Int): Boolean {
        val s = x.toString()
        for (i in 0 until (s.length / 2)) {
            if (s[i] != s[s.length - i - 1]) {
                return false
            }
        }
        return true
    }

    fun isP2(x: Int): Boolean {
        if (x < 0) return false
        var res: Int = 0
        var copy = x
        while (copy != 0) {
            res = res * 10 + copy % 10
            copy /= 10
        }
        return x == res
    }

    fun romanToInt(s: String): Int { // треш
        val map = hashMapOf<String, Int>(
            "I" to 1,
            "IV" to 4,
            "V" to 5,
            "X" to 10,
            "IX" to 9,
            "L" to 50,
            "XL" to 40,
            "C" to 100,
            "XC" to 90,
            "D" to 500,
            "CD" to 400,
            "M" to 1000,
            "CM" to 900
        )
        var res: Int = 0
        val len_w = s.length // 3
        var len_m = 0
        var i = 0
        while (len_m != len_w) {
            if (i + 1 == len_w) {
                res += map[s[i].toString()] ?: 0
                len_m += 1
            } else if (map[s[i].toString() + s[i + 1]] != null) {
                res += map[s[i].toString() + s[i + 1]] ?: 0
                i += 1
                len_m += 2
            } else {
                res += map[s[i].toString()] ?: 0
                len_m += 1
            }
            i += 1
        }
        return res
    }
    fun longestCommonPrefix(strs: Array<String>): String {
        var res = ""

        for (str in strs.indices) {

        }
        return res
    }
}

// target = a + b
// a = t - b => b?
fun main() {
    println(Solution().romanToInt("MCMXCIV"))
}