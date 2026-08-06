package io.github.aoguai.sesameag.util

import io.github.aoguai.sesameag.util.Log.record
import java.text.Collator
import java.util.Locale

/**
 * @author Constanline
 * @since 2023/08/03
 */
class HanziToPinyin protected constructor(private val mHasChinaCollator: Boolean) {
    class Token {
        constructor()

        constructor(type: Int, source: String?, target: String?) {
            this.type = type
            this.source = source
            this.target = target
        }

        /**
         * Type of this token, ASCII, PINYIN or UNKNOWN.
         */
        var type: Int = 0

        /**
         * Original string before translation.
         */
        var source: String? = null

        /**
         * Translated string of source. For Han, target is corresponding Pinyin.
         * Otherwise target is
         * original string in source.
         */
        var target: String? = null

        companion object {
            const val LATIN: Int = 1
            const val PINYIN: Int = 2
            const val UNKNOWN: Int = 3
        }
    }

    private fun getToken(character: Char): Token {
        try {
            val token = Token()
            val letter = character.toString()
            token.source = letter
            var offset = -1
            var cmp: Int
            if (character.code < 256) {
                token.type = Token.Companion.LATIN
                token.target = letter
                return token
            } else if (character < FIRST_UNIHAN) {
                token.type = Token.Companion.UNKNOWN
                token.target = letter
                return token
            } else {
                cmp = COLLATOR.compare(letter, FIRST_PINYIN_UNIHAN)
                if (cmp < 0) {
                    token.type = Token.Companion.UNKNOWN
                    token.target = letter
                    return token
                } else if (cmp == 0) {
                    token.type = Token.Companion.PINYIN
                    offset = 0
                } else {
                    cmp = COLLATOR.compare(letter, LAST_PINYIN_UNIHAN)
                    if (cmp > 0) {
                        token.type = Token.Companion.UNKNOWN
                        token.target = letter
                        return token
                    } else if (cmp == 0) {
                        token.type = Token.Companion.PINYIN
                        offset = UNIHANS.size - 1
                    }
                }
            }
            token.type = Token.Companion.PINYIN
            if (offset < 0) {
                var begin = 0
                var end: Int = UNIHANS.size - 1
                while (begin <= end) {
                    offset = (begin + end) / 2
                    val unihan = UNIHANS[offset].toString()
                    cmp = COLLATOR.compare(letter, unihan)
                    if (cmp == 0) {
                        break
                    } else if (cmp > 0) {
                        begin = offset + 1
                    } else {
                        end = offset - 1
                    }
                }
            }
            val pinyin = StringBuilder()
            var j = 0
            while (j < PINYINS[offset].size && PINYINS[offset][j].toInt() != 0) {
                pinyin.append(Char(PINYINS[offset][j].toUShort()))
                j++
            }
            token.target = pinyin.toString()
            return token
        } catch (th: Throwable) {
            record(TAG, "字符'" + character + "'转换失败")
            throw th
        }
    }

    /**
     * Convert the input to a array of tokens. The sequence of ASCII or Unknown
     * characters without
     * space will be put into a Token, One Hanzi character which has pinyin will be
     * treated as a
     * Token. If these is no China collator, the empty token array is returned.
     */
    fun get(input: String?): ArrayList<Token> {
        val tokens = ArrayList<Token>()
        if (!mHasChinaCollator || input.isNullOrEmpty()) {
            // return empty tokens.
            return tokens
        }
        try {
            val inputLength = input.length
            val sb = StringBuilder()
            var tokenType: Int = Token.Companion.LATIN
            // Go through the input, create a new token when
            // a. Token type changed
            // b. Get the Pinyin of current charater.
            // c. current character is space.
            for (i in 0..<inputLength) {
                val character = input.get(i)
                if (character == ' ') {
                    if (sb.length > 0) {
                        addToken(sb, tokens, tokenType)
                    }
                } else if (character.code < 256) {
                    if (tokenType != Token.Companion.LATIN && sb.length > 0) {
                        addToken(sb, tokens, tokenType)
                    }
                    tokenType = Token.Companion.LATIN
                    sb.append(character)
                } else if (character < FIRST_UNIHAN) {
                    if (tokenType != Token.Companion.UNKNOWN && sb.length > 0) {
                        addToken(sb, tokens, tokenType)
                    }
                    tokenType = Token.Companion.UNKNOWN
                    sb.append(character)
                } else {
                    val t = getToken(character)
                    if (t.type == Token.Companion.PINYIN) {
                        if (sb.length > 0) {
                            addToken(sb, tokens, tokenType)
                        }
                        tokens.add(t)
                        tokenType = Token.Companion.PINYIN
                    } else {
                        if (tokenType != t.type && sb.length > 0) {
                            addToken(sb, tokens, tokenType)
                        }
                        tokenType = t.type
                        sb.append(character)
                    }
                }
            }
            if (sb.length > 0) {
                addToken(sb, tokens, tokenType)
            }
            return tokens
        } catch (th: Throwable) {
            record(TAG, "字符串\"" + input + "\"转换失败")
            throw th
        }
    }

    private fun addToken(
        sb: StringBuilder, tokens: ArrayList<Token>, tokenType: Int
    ) {
        val str = sb.toString()
        tokens.add(Token(tokenType, str, str))
        sb.setLength(0)
    }

    companion object {
        private const val TAG = "HanziToPinyin"

        /**
         * Unihans array. Each unihans is the first one within same pinyin. Use it to
         * determine pinyin
         * for all ~20k unihans.
         */
        val UNIHANS: CharArray = charArrayOf(
            '呵', '哎', '安', '肮', '凹',
            '八', '挀', '扳', '邦', '包', '卑', '奔', '伻',
            '屄', '边', '标', '憋', '邠', '槟', '癶', '峬',
            '嚓', '婇', '飡', '仓', '操', '冊', '嵾', '噌',
            '叉', '钗', '辿', '伥', '抄', '车', '抻', '柽',
            '吃', '充', '抽', '出', '欻', '揣', '川', '疮',
            '吹', '杶', '逴', '疵', '匆', '凑', '粗', '汆',
            '崔', '邨', '搓', '咑', '大', '疸', '当', '刀',
            '淂', '得', '扥', '灯', '氐', '嗲', '甸', '刁',
            '爹', '仃', '丟', '东', '唗', '嘟', '偳', '堆',
            '鐓', '多', '婀', '诶', '奀', '鞥', '而', '发',
            '帆', '方', '飞', '分', '丰', '覅', '仏', '紑',
            '伕', '旮', '该', '甘', '冈', '皋', '戈', '給',
            '根', '庚', '工', '勾', '估', '瓜', '罫', '关',
            '光', '归', '衮', '呙', '哈', '咳', '顸', '苀',
            '蒿', '诃', '黒', '拫', '亨', '噷', '吽', '齁',
            '匢', '花', '怀', '犿', '巟', '灰', '昏', '吙',
            '丌', '加', '戋', '江', '艽', '阶', '巾', '劤',
            '冂', '勼', '匊', '娟', '噘', '军', '咔', '开',
            '刊', '闶', '尻', '匼', '剋', '肯', '阬', '空',
            '抠', '刳', '夸', '蒯', '宽', '匡', '亏', '坤',
            '扩', '垃', '来', '兰', '啷', '捞', '仂', '勒',
            '塄', '刕', '倆', '奁', '良', '撩', '列', '拎',
            '〇', '溜', '龙', '瞜', '噜', '娈', '畧', '抡',
            '罗', '呣', '妈', '霾', '嫚', '邙', '猫', '麼',
            '沒', '门', '甿', '咪', '眠', '喵', '咩', '民',
            '名', '谬', '摸', '哞', '毪', '拏', '孻', '囡',
            '囊', '孬', '讷', '馁', '恁', '能', '妮', '拈',
            '嬢', '鸟', '捏', '您', '宁', '妞', '农', '羺',
            '奴', '奻', '虐', '挪', '喔', '讴', '趴', '拍',
            '眅', '乓', '抛', '呸', '喷', '匉', '丕', '偏',
            '剽', '氕', '姘', '乒', '钋', '剖', '仆', '七',
            '掐', '千', '呛', '悄', '癿', '侵', '靑', '邛',
            '丘', '曲', '弮', '缺', '夋', '呥', '穣', '娆',
            '惹', '人', '扔', '日', '茸', '厹', '如', '堧',
            '桵', '闰', '若', '仨', '毢', '三', '桒', '掻',
            '色', '森', '僧', '杀', '筛', '山', '伤', '弰',
            '奢', '申', '升', '尸', '収', '书', '刷', '摔',
            '闩', '双', '谁', '吮', '妁', '厶', '忪', '捜',
            '苏', '狻', '夊', '孙', '唆', '他', '苔', '坍',
            '铴', '夲', '忑', '熥', '剔', '天', '佻', '帖',
            '厅', '囲', '偷', '鋀', '湍', '推', '吞', '托',
            '挖', '歪', '弯', '尪', '危', '塭', '翁', '挝',
            '兀', '夕', '虾', '仚', '乡', '灱', '些', '心',
            '星', '凶', '休', '旴', '轩', '疶', '勋', '丫',
            '恹', '央', '幺', '耶', '一', '欭', '应', '哟',
            '佣', '优', '扜', '鸢', '曰', '晕', '匝', '災',
            '糌', '牂', '傮', '则', '贼', '怎', '増', '吒',
            '捚', '沾', '张', '钊', '蜇', '贞', '争', '之',
            '中', '州', '朱', '抓', '跩', '专', '妆', '隹',
            '宒', '卓', '孜', '宗', '邹', '租', '钻', '厜',
            '尊', '昨',
        )

        /**
         * Pinyin array. Each pinyin is corresponding to unihans of same offset in the
         * unihans array.
         */
        val PINYINS: Array<ByteArray> = arrayOf(
            byteArrayOf(65, 0, 0, 0, 0, 0),
            byteArrayOf(65, 73, 0, 0, 0, 0),
            byteArrayOf(65, 78, 0, 0, 0, 0),
            byteArrayOf(65, 78, 71, 0, 0, 0),
            byteArrayOf(65, 79, 0, 0, 0, 0),
            byteArrayOf(66, 65, 0, 0, 0, 0),
            byteArrayOf(66, 65, 73, 0, 0, 0),
            byteArrayOf(66, 65, 78, 0, 0, 0),
            byteArrayOf(66, 65, 78, 71, 0, 0),
            byteArrayOf(66, 65, 79, 0, 0, 0),
            byteArrayOf(66, 69, 73, 0, 0, 0),
            byteArrayOf(66, 69, 78, 0, 0, 0),
            byteArrayOf(66, 69, 78, 71, 0, 0),
            byteArrayOf(66, 73, 0, 0, 0, 0),
            byteArrayOf(66, 73, 65, 78, 0, 0),
            byteArrayOf(66, 73, 65, 79, 0, 0),
            byteArrayOf(66, 73, 69, 0, 0, 0),
            byteArrayOf(66, 73, 78, 0, 0, 0),
            byteArrayOf(66, 73, 78, 71, 0, 0),
            byteArrayOf(66, 79, 0, 0, 0, 0),
            byteArrayOf(66, 85, 0, 0, 0, 0),
            byteArrayOf(67, 65, 0, 0, 0, 0),
            byteArrayOf(67, 65, 73, 0, 0, 0),
            byteArrayOf(67, 65, 78, 0, 0, 0),
            byteArrayOf(67, 65, 78, 71, 0, 0),
            byteArrayOf(67, 65, 79, 0, 0, 0),
            byteArrayOf(67, 69, 0, 0, 0, 0),
            byteArrayOf(67, 69, 78, 0, 0, 0),
            byteArrayOf(67, 69, 78, 71, 0, 0),
            byteArrayOf(67, 72, 65, 0, 0, 0),
            byteArrayOf(67, 72, 65, 73, 0, 0),
            byteArrayOf(67, 72, 65, 78, 0, 0),
            byteArrayOf(67, 72, 65, 78, 71, 0),
            byteArrayOf(67, 72, 65, 79, 0, 0),
            byteArrayOf(67, 72, 69, 0, 0, 0),
            byteArrayOf(67, 72, 69, 78, 0, 0),
            byteArrayOf(67, 72, 69, 78, 71, 0),
            byteArrayOf(67, 72, 73, 0, 0, 0),
            byteArrayOf(67, 72, 79, 78, 71, 0),
            byteArrayOf(67, 72, 79, 85, 0, 0),
            byteArrayOf(67, 72, 85, 0, 0, 0),
            byteArrayOf(67, 72, 85, 65, 0, 0),
            byteArrayOf(67, 72, 85, 65, 73, 0),
            byteArrayOf(67, 72, 85, 65, 78, 0),
            byteArrayOf(67, 72, 85, 65, 78, 71),
            byteArrayOf(67, 72, 85, 73, 0, 0),
            byteArrayOf(67, 72, 85, 78, 0, 0),
            byteArrayOf(67, 72, 85, 79, 0, 0),
            byteArrayOf(67, 73, 0, 0, 0, 0),
            byteArrayOf(67, 79, 78, 71, 0, 0),
            byteArrayOf(67, 79, 85, 0, 0, 0),
            byteArrayOf(67, 85, 0, 0, 0, 0),
            byteArrayOf(67, 85, 65, 78, 0, 0),
            byteArrayOf(67, 85, 73, 0, 0, 0),
            byteArrayOf(67, 85, 78, 0, 0, 0),
            byteArrayOf(67, 85, 79, 0, 0, 0),
            byteArrayOf(68, 65, 0, 0, 0, 0),
            byteArrayOf(68, 65, 73, 0, 0, 0),
            byteArrayOf(68, 65, 78, 0, 0, 0),
            byteArrayOf(68, 65, 78, 71, 0, 0),
            byteArrayOf(68, 65, 79, 0, 0, 0),
            byteArrayOf(68, 69, 0, 0, 0, 0),
            byteArrayOf(68, 69, 73, 0, 0, 0),
            byteArrayOf(68, 69, 78, 0, 0, 0),
            byteArrayOf(68, 69, 78, 71, 0, 0),
            byteArrayOf(68, 73, 0, 0, 0, 0),
            byteArrayOf(68, 73, 65, 0, 0, 0),
            byteArrayOf(68, 73, 65, 78, 0, 0),
            byteArrayOf(68, 73, 65, 79, 0, 0),
            byteArrayOf(68, 73, 69, 0, 0, 0),
            byteArrayOf(68, 73, 78, 71, 0, 0),
            byteArrayOf(68, 73, 85, 0, 0, 0),
            byteArrayOf(68, 79, 78, 71, 0, 0),
            byteArrayOf(68, 79, 85, 0, 0, 0),
            byteArrayOf(68, 85, 0, 0, 0, 0),
            byteArrayOf(68, 85, 65, 78, 0, 0),
            byteArrayOf(68, 85, 73, 0, 0, 0),
            byteArrayOf(68, 85, 78, 0, 0, 0),
            byteArrayOf(68, 85, 79, 0, 0, 0),
            byteArrayOf(69, 0, 0, 0, 0, 0),
            byteArrayOf(69, 73, 0, 0, 0, 0),
            byteArrayOf(69, 78, 0, 0, 0, 0),
            byteArrayOf(69, 78, 71, 0, 0, 0),
            byteArrayOf(69, 82, 0, 0, 0, 0),
            byteArrayOf(70, 65, 0, 0, 0, 0),
            byteArrayOf(70, 65, 78, 0, 0, 0),
            byteArrayOf(70, 65, 78, 71, 0, 0),
            byteArrayOf(70, 69, 73, 0, 0, 0),
            byteArrayOf(70, 69, 78, 0, 0, 0),
            byteArrayOf(70, 69, 78, 71, 0, 0),
            byteArrayOf(70, 73, 65, 79, 0, 0),
            byteArrayOf(70, 79, 0, 0, 0, 0),
            byteArrayOf(70, 79, 85, 0, 0, 0),
            byteArrayOf(70, 85, 0, 0, 0, 0),
            byteArrayOf(71, 65, 0, 0, 0, 0),
            byteArrayOf(71, 65, 73, 0, 0, 0),
            byteArrayOf(71, 65, 78, 0, 0, 0),
            byteArrayOf(71, 65, 78, 71, 0, 0),
            byteArrayOf(71, 65, 79, 0, 0, 0),
            byteArrayOf(71, 69, 0, 0, 0, 0),
            byteArrayOf(71, 69, 73, 0, 0, 0),
            byteArrayOf(71, 69, 78, 0, 0, 0),
            byteArrayOf(71, 69, 78, 71, 0, 0),
            byteArrayOf(71, 79, 78, 71, 0, 0),
            byteArrayOf(71, 79, 85, 0, 0, 0),
            byteArrayOf(71, 85, 0, 0, 0, 0),
            byteArrayOf(71, 85, 65, 0, 0, 0),
            byteArrayOf(71, 85, 65, 73, 0, 0),
            byteArrayOf(71, 85, 65, 78, 0, 0),
            byteArrayOf(71, 85, 65, 78, 71, 0),
            byteArrayOf(71, 85, 73, 0, 0, 0),
            byteArrayOf(71, 85, 78, 0, 0, 0),
            byteArrayOf(71, 85, 79, 0, 0, 0),
            byteArrayOf(72, 65, 0, 0, 0, 0),
            byteArrayOf(72, 65, 73, 0, 0, 0),
            byteArrayOf(72, 65, 78, 0, 0, 0),
            byteArrayOf(72, 65, 78, 71, 0, 0),
            byteArrayOf(72, 65, 79, 0, 0, 0),
            byteArrayOf(72, 69, 0, 0, 0, 0),
            byteArrayOf(72, 69, 73, 0, 0, 0),
            byteArrayOf(72, 69, 78, 0, 0, 0),
            byteArrayOf(72, 69, 78, 71, 0, 0),
            byteArrayOf(72, 77, 0, 0, 0, 0),
            byteArrayOf(72, 79, 78, 71, 0, 0),
            byteArrayOf(72, 79, 85, 0, 0, 0),
            byteArrayOf(72, 85, 0, 0, 0, 0),
            byteArrayOf(72, 85, 65, 0, 0, 0),
            byteArrayOf(72, 85, 65, 73, 0, 0),
            byteArrayOf(72, 85, 65, 78, 0, 0),
            byteArrayOf(72, 85, 65, 78, 71, 0),
            byteArrayOf(72, 85, 73, 0, 0, 0),
            byteArrayOf(72, 85, 78, 0, 0, 0),
            byteArrayOf(72, 85, 79, 0, 0, 0),
            byteArrayOf(74, 73, 0, 0, 0, 0),
            byteArrayOf(74, 73, 65, 0, 0, 0),
            byteArrayOf(74, 73, 65, 78, 0, 0),
            byteArrayOf(74, 73, 65, 78, 71, 0),
            byteArrayOf(74, 73, 65, 79, 0, 0),
            byteArrayOf(74, 73, 69, 0, 0, 0),
            byteArrayOf(74, 73, 78, 0, 0, 0),
            byteArrayOf(74, 73, 78, 71, 0, 0),
            byteArrayOf(74, 73, 79, 78, 71, 0),
            byteArrayOf(74, 73, 85, 0, 0, 0),
            byteArrayOf(74, 85, 0, 0, 0, 0),
            byteArrayOf(74, 85, 65, 78, 0, 0),
            byteArrayOf(74, 85, 69, 0, 0, 0),
            byteArrayOf(74, 85, 78, 0, 0, 0),
            byteArrayOf(75, 65, 0, 0, 0, 0),
            byteArrayOf(75, 65, 73, 0, 0, 0),
            byteArrayOf(75, 65, 78, 0, 0, 0),
            byteArrayOf(75, 65, 78, 71, 0, 0),
            byteArrayOf(75, 65, 79, 0, 0, 0),
            byteArrayOf(75, 69, 0, 0, 0, 0),
            byteArrayOf(75, 69, 73, 0, 0, 0),
            byteArrayOf(75, 69, 78, 0, 0, 0),
            byteArrayOf(75, 69, 78, 71, 0, 0),
            byteArrayOf(75, 79, 78, 71, 0, 0),
            byteArrayOf(75, 79, 85, 0, 0, 0),
            byteArrayOf(75, 85, 0, 0, 0, 0),
            byteArrayOf(75, 85, 65, 0, 0, 0),
            byteArrayOf(75, 85, 65, 73, 0, 0),
            byteArrayOf(75, 85, 65, 78, 0, 0),
            byteArrayOf(75, 85, 65, 78, 71, 0),
            byteArrayOf(75, 85, 73, 0, 0, 0),
            byteArrayOf(75, 85, 78, 0, 0, 0),
            byteArrayOf(75, 85, 79, 0, 0, 0),
            byteArrayOf(76, 65, 0, 0, 0, 0),
            byteArrayOf(76, 65, 73, 0, 0, 0),
            byteArrayOf(76, 65, 78, 0, 0, 0),
            byteArrayOf(76, 65, 78, 71, 0, 0),
            byteArrayOf(76, 65, 79, 0, 0, 0),
            byteArrayOf(76, 69, 0, 0, 0, 0),
            byteArrayOf(76, 69, 73, 0, 0, 0),
            byteArrayOf(76, 69, 78, 71, 0, 0),
            byteArrayOf(76, 73, 0, 0, 0, 0),
            byteArrayOf(76, 73, 65, 0, 0, 0),
            byteArrayOf(76, 73, 65, 78, 0, 0),
            byteArrayOf(76, 73, 65, 78, 71, 0),
            byteArrayOf(76, 73, 65, 79, 0, 0),
            byteArrayOf(76, 73, 69, 0, 0, 0),
            byteArrayOf(76, 73, 78, 0, 0, 0),
            byteArrayOf(76, 73, 78, 71, 0, 0),
            byteArrayOf(76, 73, 85, 0, 0, 0),
            byteArrayOf(76, 79, 78, 71, 0, 0),
            byteArrayOf(76, 79, 85, 0, 0, 0),
            byteArrayOf(76, 85, 0, 0, 0, 0),
            byteArrayOf(76, 85, 65, 78, 0, 0),
            byteArrayOf(76, 85, 69, 0, 0, 0),
            byteArrayOf(76, 85, 78, 0, 0, 0),
            byteArrayOf(76, 85, 79, 0, 0, 0),
            byteArrayOf(77, 0, 0, 0, 0, 0),
            byteArrayOf(77, 65, 0, 0, 0, 0),
            byteArrayOf(77, 65, 73, 0, 0, 0),
            byteArrayOf(77, 65, 78, 0, 0, 0),
            byteArrayOf(77, 65, 78, 71, 0, 0),
            byteArrayOf(77, 65, 79, 0, 0, 0),
            byteArrayOf(77, 69, 0, 0, 0, 0),
            byteArrayOf(77, 69, 73, 0, 0, 0),
            byteArrayOf(77, 69, 78, 0, 0, 0),
            byteArrayOf(77, 69, 78, 71, 0, 0),
            byteArrayOf(77, 73, 0, 0, 0, 0),
            byteArrayOf(77, 73, 65, 78, 0, 0),
            byteArrayOf(77, 73, 65, 79, 0, 0),
            byteArrayOf(77, 73, 69, 0, 0, 0),
            byteArrayOf(77, 73, 78, 0, 0, 0),
            byteArrayOf(77, 73, 78, 71, 0, 0),
            byteArrayOf(77, 73, 85, 0, 0, 0),
            byteArrayOf(77, 79, 0, 0, 0, 0),
            byteArrayOf(77, 79, 85, 0, 0, 0),
            byteArrayOf(77, 85, 0, 0, 0, 0),
            byteArrayOf(78, 65, 0, 0, 0, 0),
            byteArrayOf(78, 65, 73, 0, 0, 0),
            byteArrayOf(78, 65, 78, 0, 0, 0),
            byteArrayOf(78, 65, 78, 71, 0, 0),
            byteArrayOf(78, 65, 79, 0, 0, 0),
            byteArrayOf(78, 69, 0, 0, 0, 0),
            byteArrayOf(78, 69, 73, 0, 0, 0),
            byteArrayOf(78, 69, 78, 0, 0, 0),
            byteArrayOf(78, 69, 78, 71, 0, 0),
            byteArrayOf(78, 73, 0, 0, 0, 0),
            byteArrayOf(78, 73, 65, 78, 0, 0),
            byteArrayOf(78, 73, 65, 78, 71, 0),
            byteArrayOf(78, 73, 65, 79, 0, 0),
            byteArrayOf(78, 73, 69, 0, 0, 0),
            byteArrayOf(78, 73, 78, 0, 0, 0),
            byteArrayOf(78, 73, 78, 71, 0, 0),
            byteArrayOf(78, 73, 85, 0, 0, 0),
            byteArrayOf(78, 79, 78, 71, 0, 0),
            byteArrayOf(78, 79, 85, 0, 0, 0),
            byteArrayOf(78, 85, 0, 0, 0, 0),
            byteArrayOf(78, 85, 65, 78, 0, 0),
            byteArrayOf(78, 85, 69, 0, 0, 0),
            byteArrayOf(78, 85, 79, 0, 0, 0),
            byteArrayOf(79, 0, 0, 0, 0, 0),
            byteArrayOf(79, 85, 0, 0, 0, 0),
            byteArrayOf(80, 65, 0, 0, 0, 0),
            byteArrayOf(80, 65, 73, 0, 0, 0),
            byteArrayOf(80, 65, 78, 0, 0, 0),
            byteArrayOf(80, 65, 78, 71, 0, 0),
            byteArrayOf(80, 65, 79, 0, 0, 0),
            byteArrayOf(80, 69, 73, 0, 0, 0),
            byteArrayOf(80, 69, 78, 0, 0, 0),
            byteArrayOf(80, 69, 78, 71, 0, 0),
            byteArrayOf(80, 73, 0, 0, 0, 0),
            byteArrayOf(80, 73, 65, 78, 0, 0),
            byteArrayOf(80, 73, 65, 79, 0, 0),
            byteArrayOf(80, 73, 69, 0, 0, 0),
            byteArrayOf(80, 73, 78, 0, 0, 0),
            byteArrayOf(80, 73, 78, 71, 0, 0),
            byteArrayOf(80, 79, 0, 0, 0, 0),
            byteArrayOf(80, 79, 85, 0, 0, 0),
            byteArrayOf(80, 85, 0, 0, 0, 0),
            byteArrayOf(81, 73, 0, 0, 0, 0),
            byteArrayOf(81, 73, 65, 0, 0, 0),
            byteArrayOf(81, 73, 65, 78, 0, 0),
            byteArrayOf(81, 73, 65, 78, 71, 0),
            byteArrayOf(81, 73, 65, 79, 0, 0),
            byteArrayOf(81, 73, 69, 0, 0, 0),
            byteArrayOf(81, 73, 78, 0, 0, 0),
            byteArrayOf(81, 73, 78, 71, 0, 0),
            byteArrayOf(81, 73, 79, 78, 71, 0),
            byteArrayOf(81, 73, 85, 0, 0, 0),
            byteArrayOf(81, 85, 0, 0, 0, 0),
            byteArrayOf(81, 85, 65, 78, 0, 0),
            byteArrayOf(81, 85, 69, 0, 0, 0),
            byteArrayOf(81, 85, 78, 0, 0, 0),
            byteArrayOf(82, 65, 78, 0, 0, 0),
            byteArrayOf(82, 65, 78, 71, 0, 0),
            byteArrayOf(82, 65, 79, 0, 0, 0),
            byteArrayOf(82, 69, 0, 0, 0, 0),
            byteArrayOf(82, 69, 78, 0, 0, 0),
            byteArrayOf(82, 69, 78, 71, 0, 0),
            byteArrayOf(82, 73, 0, 0, 0, 0),
            byteArrayOf(82, 79, 78, 71, 0, 0),
            byteArrayOf(82, 79, 85, 0, 0, 0),
            byteArrayOf(82, 85, 0, 0, 0, 0),
            byteArrayOf(82, 85, 65, 78, 0, 0),
            byteArrayOf(82, 85, 73, 0, 0, 0),
            byteArrayOf(82, 85, 78, 0, 0, 0),
            byteArrayOf(82, 85, 79, 0, 0, 0),
            byteArrayOf(83, 65, 0, 0, 0, 0),
            byteArrayOf(83, 65, 73, 0, 0, 0),
            byteArrayOf(83, 65, 78, 0, 0, 0),
            byteArrayOf(83, 65, 78, 71, 0, 0),
            byteArrayOf(83, 65, 79, 0, 0, 0),
            byteArrayOf(83, 69, 0, 0, 0, 0),
            byteArrayOf(83, 69, 78, 0, 0, 0),
            byteArrayOf(83, 69, 78, 71, 0, 0),
            byteArrayOf(83, 72, 65, 0, 0, 0),
            byteArrayOf(83, 72, 65, 73, 0, 0),
            byteArrayOf(83, 72, 65, 78, 0, 0),
            byteArrayOf(83, 72, 65, 78, 71, 0),
            byteArrayOf(83, 72, 65, 79, 0, 0),
            byteArrayOf(83, 72, 69, 0, 0, 0),
            byteArrayOf(83, 72, 69, 78, 0, 0),
            byteArrayOf(83, 72, 69, 78, 71, 0),
            byteArrayOf(83, 72, 73, 0, 0, 0),
            byteArrayOf(83, 72, 79, 85, 0, 0),
            byteArrayOf(83, 72, 85, 0, 0, 0),
            byteArrayOf(83, 72, 85, 65, 0, 0),
            byteArrayOf(83, 72, 85, 65, 73, 0),
            byteArrayOf(83, 72, 85, 65, 78, 0),
            byteArrayOf(83, 72, 85, 65, 78, 71),
            byteArrayOf(83, 72, 85, 73, 0, 0),
            byteArrayOf(83, 72, 85, 78, 0, 0),
            byteArrayOf(83, 72, 85, 79, 0, 0),
            byteArrayOf(83, 73, 0, 0, 0, 0),
            byteArrayOf(83, 79, 78, 71, 0, 0),
            byteArrayOf(83, 79, 85, 0, 0, 0),
            byteArrayOf(83, 85, 0, 0, 0, 0),
            byteArrayOf(83, 85, 65, 78, 0, 0),
            byteArrayOf(83, 85, 73, 0, 0, 0),
            byteArrayOf(83, 85, 78, 0, 0, 0),
            byteArrayOf(83, 85, 79, 0, 0, 0),
            byteArrayOf(84, 65, 0, 0, 0, 0),
            byteArrayOf(84, 65, 73, 0, 0, 0),
            byteArrayOf(84, 65, 78, 0, 0, 0),
            byteArrayOf(84, 65, 78, 71, 0, 0),
            byteArrayOf(84, 65, 79, 0, 0, 0),
            byteArrayOf(84, 69, 0, 0, 0, 0),
            byteArrayOf(84, 69, 78, 71, 0, 0),
            byteArrayOf(84, 73, 0, 0, 0, 0),
            byteArrayOf(84, 73, 65, 78, 0, 0),
            byteArrayOf(84, 73, 65, 79, 0, 0),
            byteArrayOf(84, 73, 69, 0, 0, 0),
            byteArrayOf(84, 73, 78, 71, 0, 0),
            byteArrayOf(84, 79, 78, 71, 0, 0),
            byteArrayOf(84, 79, 85, 0, 0, 0),
            byteArrayOf(84, 85, 0, 0, 0, 0),
            byteArrayOf(84, 85, 65, 78, 0, 0),
            byteArrayOf(84, 85, 73, 0, 0, 0),
            byteArrayOf(84, 85, 78, 0, 0, 0),
            byteArrayOf(84, 85, 79, 0, 0, 0),
            byteArrayOf(87, 65, 0, 0, 0, 0),
            byteArrayOf(87, 65, 73, 0, 0, 0),
            byteArrayOf(87, 65, 78, 0, 0, 0),
            byteArrayOf(87, 65, 78, 71, 0, 0),
            byteArrayOf(87, 69, 73, 0, 0, 0),
            byteArrayOf(87, 69, 78, 0, 0, 0),
            byteArrayOf(87, 69, 78, 71, 0, 0),
            byteArrayOf(87, 79, 0, 0, 0, 0),
            byteArrayOf(87, 85, 0, 0, 0, 0),
            byteArrayOf(88, 73, 0, 0, 0, 0),
            byteArrayOf(88, 73, 65, 0, 0, 0),
            byteArrayOf(88, 73, 65, 78, 0, 0),
            byteArrayOf(88, 73, 65, 78, 71, 0),
            byteArrayOf(88, 73, 65, 79, 0, 0),
            byteArrayOf(88, 73, 69, 0, 0, 0),
            byteArrayOf(88, 73, 78, 0, 0, 0),
            byteArrayOf(88, 73, 78, 71, 0, 0),
            byteArrayOf(88, 73, 79, 78, 71, 0),
            byteArrayOf(88, 73, 85, 0, 0, 0),
            byteArrayOf(88, 85, 0, 0, 0, 0),
            byteArrayOf(88, 85, 65, 78, 0, 0),
            byteArrayOf(88, 85, 69, 0, 0, 0),
            byteArrayOf(88, 85, 78, 0, 0, 0),
            byteArrayOf(89, 65, 0, 0, 0, 0),
            byteArrayOf(89, 65, 78, 0, 0, 0),
            byteArrayOf(89, 65, 78, 71, 0, 0),
            byteArrayOf(89, 65, 79, 0, 0, 0),
            byteArrayOf(89, 69, 0, 0, 0, 0),
            byteArrayOf(89, 73, 0, 0, 0, 0),
            byteArrayOf(89, 73, 78, 0, 0, 0),
            byteArrayOf(89, 73, 78, 71, 0, 0),
            byteArrayOf(89, 79, 0, 0, 0, 0),
            byteArrayOf(89, 79, 78, 71, 0, 0),
            byteArrayOf(89, 79, 85, 0, 0, 0),
            byteArrayOf(89, 85, 0, 0, 0, 0),
            byteArrayOf(89, 85, 65, 78, 0, 0),
            byteArrayOf(89, 85, 69, 0, 0, 0),
            byteArrayOf(89, 85, 78, 0, 0, 0),
            byteArrayOf(90, 65, 0, 0, 0, 0),
            byteArrayOf(90, 65, 73, 0, 0, 0),
            byteArrayOf(90, 65, 78, 0, 0, 0),
            byteArrayOf(90, 65, 78, 71, 0, 0),
            byteArrayOf(90, 65, 79, 0, 0, 0),
            byteArrayOf(90, 69, 0, 0, 0, 0),
            byteArrayOf(90, 69, 73, 0, 0, 0),
            byteArrayOf(90, 69, 78, 0, 0, 0),
            byteArrayOf(90, 69, 78, 71, 0, 0),
            byteArrayOf(90, 72, 65, 0, 0, 0),
            byteArrayOf(90, 72, 65, 73, 0, 0),
            byteArrayOf(90, 72, 65, 78, 0, 0),
            byteArrayOf(90, 72, 65, 78, 71, 0),
            byteArrayOf(90, 72, 65, 79, 0, 0),
            byteArrayOf(90, 72, 69, 0, 0, 0),
            byteArrayOf(90, 72, 69, 78, 0, 0),
            byteArrayOf(90, 72, 69, 78, 71, 0),
            byteArrayOf(90, 72, 73, 0, 0, 0),
            byteArrayOf(90, 72, 79, 78, 71, 0),
            byteArrayOf(90, 72, 79, 85, 0, 0),
            byteArrayOf(90, 72, 85, 0, 0, 0),
            byteArrayOf(90, 72, 85, 65, 0, 0),
            byteArrayOf(90, 72, 85, 65, 73, 0),
            byteArrayOf(90, 72, 85, 65, 78, 0),
            byteArrayOf(90, 72, 85, 65, 78, 71),
            byteArrayOf(90, 72, 85, 73, 0, 0),
            byteArrayOf(90, 72, 85, 78, 0, 0),
            byteArrayOf(90, 72, 85, 79, 0, 0),
            byteArrayOf(90, 73, 0, 0, 0, 0),
            byteArrayOf(90, 79, 78, 71, 0, 0),
            byteArrayOf(90, 79, 85, 0, 0, 0),
            byteArrayOf(90, 85, 0, 0, 0, 0),
            byteArrayOf(90, 85, 65, 78, 0, 0),
            byteArrayOf(90, 85, 73, 0, 0, 0),
            byteArrayOf(90, 85, 78, 0, 0, 0),
            byteArrayOf(90, 85, 79, 0, 0, 0),
        )

        /**
         * First and last Chinese character with known Pinyin according to zh collation
         */
        private const val FIRST_PINYIN_UNIHAN = "阿"
        private const val LAST_PINYIN_UNIHAN = "蓙"

        /**
         * The first Chinese character in Unicode block
         */
        private const val FIRST_UNIHAN = '㐀'
        private val COLLATOR: Collator = Collator.getInstance(Locale.CHINA)
        private var sInstance: HanziToPinyin? = null
        
        @JvmStatic
        fun getInstance(): HanziToPinyin {
            synchronized(HanziToPinyin::class.java) {
                return sInstance ?: HanziToPinyin(true).also { sInstance = it }
            }
        }
    }
}

