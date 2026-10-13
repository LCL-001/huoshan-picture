import { generateService } from '@umijs/openapi'

// int64 → string：后端 JsonConfig 把 Long 统一序列化成字符串（防 JS 精度丢失），
// 类型按 number 声明会诱导调用方写 Number(id)、把 19 位雪花 id 截断。
// 注意数组项：defaultGetType 走数组分支时直接调 defaultGetType、不再经过本钩子，故需在此显式处理。
// 另注：本仓**不自动跑生成器**（docs/decisions/2026-09-15-topic10.md，它会清空 src/api/ 并丢手写文件）；
// 此配置只为"将来真要恢复生成器"时产出类型天然正确。
const int64ToString = (schemaObject, namespace, originGetType) => {
  if (schemaObject?.format === 'int64') return 'string'
  if (schemaObject?.type === 'array' && schemaObject.items?.format === 'int64') return 'string[]'
  return originGetType(schemaObject, namespace)
}

generateService({
  requestLibPath: "import request from '@/request'",
  schemaPath: 'http://localhost:8123/api/v2/api-docs',
  serversPath: './src',
  hook: {
    customType: int64ToString,
  },
})
