// 浏览器原生 Web Crypto 不提供 MD5，因此这里保留一个轻量的前端实现。
// 只用于“根据文件内容生成本地唯一标识符”和与后端 paper_md5 语义保持一致，不用于安全场景。

/**
 * 根据文件内容计算 MD5 哈希值
 * @param file 传入的文件对象
 * @returns 包含 MD5 哈希结果的十六进制字符串
 */
export async function calculateFileMd5(file: File) {
  // 将文件转换为 ArrayBuffer
  const buffer = await file.arrayBuffer()
  // 调用核心的 MD5 计算函数
  return md5ArrayBuffer(buffer)
}

/**
 * 核心 MD5 算法实现
 * 基于 RFC 1321 标准
 * @param buffer 输入数据的 ArrayBuffer
 * @returns MD5 十六进制字符串
 */
function md5ArrayBuffer(buffer: ArrayBuffer) {
  // 将输入转换为 8 位无符号整数数组以便操作
  const source = new Uint8Array(buffer)
  // 获取原始数据的总位(bit)数
  const bitLength = source.length * 8
  // 计算补位后的字节长度。
  // MD5 要求数据长度对 512 取模为 448 (即对 64 字节取模为 56 字节)。
  // source.length + 9 预留了 1 个补位字节(0x80) 和 8 个字节的长度信息。
  // (+ 63) >> 6 << 6 的作用是向上取整到 64 的倍数。
  const paddedLength = (((source.length + 9 + 63) >> 6) << 6)
  // 创建一个补位后的数组，初始全为 0
  const padded = new Uint8Array(paddedLength)

  // 1. 数据填充 (Padding)
  // 拷贝原始数据
  padded.set(source)
  // 在原始数据后追加一个 1 bit (0x80 = 10000000)
  padded[source.length] = 0x80

  // 2. 追加长度信息
  // 使用 DataView 方便地以指定字节序写入数据
  const view = new DataView(padded.buffer)
  // 在最后 8 个字节（即倒数两个 32位整数）以小端序存入原始数据的位(bit)长度
  // 低 32 位
  view.setUint32(paddedLength - 8, bitLength >>> 0, true)
  // 高 32 位
  view.setUint32(paddedLength - 4, Math.floor(bitLength / 0x100000000), true)

  // 3. 初始化 MD5 缓冲 (幻数)
  // RFC 1321 中定义的初始四个 32 位寄存器状态
  let a = 0x67452301
  let b = 0xefcdab89
  let c = 0x98badcfe
  let d = 0x10325476

  // 4. 处理数据块 (以 64 字节为一个分组进行处理)
  for (let offset = 0; offset < paddedLength; offset += 64) {
    // 将 64 字节的分组划分为 16 个 32位(4字节)整数
    const words = new Array<number>(16)

    for (let index = 0; index < 16; index += 1) {
      // 从 DataView 中以小端序读取 32 位整数
      words[index] = view.getUint32(offset + index * 4, true)
    }

    // 保存当前分组处理前的寄存器状态
    const originalA = a
    const originalB = b
    const originalC = c
    const originalD = d

    // 执行 64 次主循环操作 (分为 4 轮，每轮 16 次)
    for (let index = 0; index < 64; index += 1) {
      let f = 0 // 每一轮的非线性函数计算结果
      let g = 0 // 每一轮选择的 32位整数索引 (0-15)

      // 第一轮 (0 - 15)
      if (index < 16) {
        f = (b & c) | (~b & d) // 函数 F
        g = index
      // 第二轮 (16 - 31)
      } else if (index < 32) {
        f = (d & b) | (~d & c) // 函数 G
        g = (5 * index + 1) % 16
      // 第三轮 (32 - 47)
      } else if (index < 48) {
        f = b ^ c ^ d          // 函数 H
        g = (3 * index + 5) % 16
      // 第四轮 (48 - 63)
      } else {
        f = c ^ (b | ~d)       // 函数 I
        g = (7 * index) % 16
      }

      const nextD = d
      d = c
      c = b
      // 核心迭代计算：
      // b = b + ((a + f(b,c,d) + words[g] + MD5_K[index]) <<< MD5_S[index])
      b = add32(b, rotateLeft(add32(add32(a, f), add32(MD5_K[index] ?? 0, words[g] ?? 0)), MD5_S[index] ?? 0))
      a = nextD
    }

    // 将本分组计算后的结果与本分组前的寄存器状态相加
    a = add32(a, originalA)
    b = add32(b, originalB)
    c = add32(c, originalC)
    d = add32(d, originalD)
  }

  // 5. 输出结果
  // 将四个 32位 整数转换为小端序的十六进制字符串并拼接
  return [a, b, c, d].map(toLittleEndianHex).join('')
}

/**
 * 32位无符号整数加法，防止 JavaScript 中相加后溢出或变为浮点数
 */
function add32(left: number, right: number) {
  return (left + right) >>> 0
}

/**
 * 32位无符号整数循环左移
 * @param value 要左移的值
 * @param shift 左移的位数
 */
function rotateLeft(value: number, shift: number) {
  return ((value << shift) | (value >>> (32 - shift))) >>> 0
}

/**
 * 将 32 位整数转换为小端序的十六进制字符串
 */
function toLittleEndianHex(value: number) {
  const hex: string[] = []

  // 按字节 (8位) 分割，共 4 个字节，每次取低 8 位转换为两位的十六进制
  for (let index = 0; index < 4; index += 1) {
    hex.push(((value >>> (index * 8)) & 0xff).toString(16).padStart(2, '0'))
  }

  return hex.join('')
}

// 每次操作中指定的循环左移位数常数表 (MD5 标准固定值)
const MD5_S = [
  7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, // 第一轮使用
  5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,     // 第二轮使用
  4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, // 第三轮使用
  6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, // 第四轮使用
]

// 64个 32 位常数数组。由 2^32 * abs(sin(i)) 的整数部分产生，i 为弧度 (1 到 64)
const MD5_K = Array.from({ length: 64 }, (_, index) =>
  Math.floor(Math.abs(Math.sin(index + 1)) * 0x100000000) >>> 0,
)
