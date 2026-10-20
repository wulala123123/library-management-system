import schema from './memberFinanceWriteSchema.json'
import { financeId, financeUtc, parseFinanceJson } from './memberFinance'
type Node = Record<string, any>
const has=(v:object,k:PropertyKey)=>Object.prototype.hasOwnProperty.call(v,k)
const definitions: Record<string, Node> = schema.$defs
const same = (a: unknown, b: unknown): boolean => JSON.stringify(a) === JSON.stringify(b)
const object = (v: unknown): v is Record<string, unknown> => v !== null && typeof v === 'object' && !Array.isArray(v)
/** Implements exactly the assertions present in the frozen contract, including conditionals. */
function matches(n: Node, v: unknown, depth = 0): boolean {
  if (depth > 100) return false
  const test = (child: Node, value = v) => matches(child, value, depth + 1)
  if (n.$ref && !test(definitions[n.$ref.split('/').pop()!])) return false
  if ('const' in n && !same(n.const, v) || n.enum && !n.enum.some((x: unknown) => same(x, v))) return false
  if (n.allOf && !n.allOf.every((x: Node) => test(x)) || n.anyOf && !n.anyOf.some((x: Node) => test(x)) || n.oneOf && n.oneOf.filter((x: Node) => test(x)).length !== 1 || n.not && test(n.not)) return false
  if (n.if && !(test(n.if) ? !n.then || test(n.then) : !n.else || test(n.else))) return false
  if (n.type && !({object: object(v), array: Array.isArray(v), string: typeof v === 'string', integer: typeof v === 'number' && Number.isInteger(v), number: typeof v === 'number' && Number.isFinite(v), boolean: typeof v === 'boolean', null: v === null} as Record<string, boolean>)[n.type]) return false
  if (typeof v === 'string') {
    if ([...v].some(c => /^[\ud800-\udfff]$/u.test(c))) return false
    if (n.minLength !== undefined && [...v].length < n.minLength || n.maxLength !== undefined && [...v].length > n.maxLength) return false
    if (n.pattern) { const match = new RegExp(n.pattern, 'u').exec(v); if (!match || match.index !== 0 || match[0] !== v) return false }
    if (n.format === 'date-time') {
      if (!/^[1-9]\d{3}-\d\d-\d\dT(?:[01]\d|2[0-3]):[0-5]\d:[0-5]\d(?:\.\d{1,6})?(?:Z|[+-](?:(?:0\d|1[0-3]):[0-5]\d|14:00))$/.test(v)) return false
      const local = v.slice(0,19), day = new Date(local + 'Z'); if (isNaN(day.getTime()) || day.toISOString().slice(0,19) !== local) return false
    }
  }
  if (typeof v === 'number' && (n.minimum !== undefined && v < n.minimum || n.maximum !== undefined && v > n.maximum)) return false
  if (Array.isArray(v)) {
    if (n.minItems !== undefined && v.length < n.minItems || n.maxItems !== undefined && v.length > n.maxItems) return false
    if (n.prefixItems && !n.prefixItems.every((x: Node, i: number) => i >= v.length || test(x,v[i]))) return false
    if (n.items && !v.slice(n.prefixItems?.length ?? 0).every(x => test(n.items,x))) return false
  }
  if (object(v)) {
    if (n.required && !n.required.every((key: string) => has(v,key))) return false
    if (n.properties && !Object.entries(n.properties).every(([key,x]) => !has(v,key) || test(x as Node,v[key]))) return false
    if (n.additionalProperties === false && Object.keys(v).some(key => !has(n.properties ?? {},key))) return false
  }
  return true
}
export function validWriteDefinition(name: string, value: unknown): boolean {
  if (!definitions[name] || !matches(definitions[name],value)) return false
  if (['Id','Revision','Epoch'].includes(name) && !(value==='0' && name==='Revision') && !financeId(value)) return false
  if (name === 'Utc' && !financeUtc(value)) return false
  // Apply runtime ID bounds and strict canonical time checks to every referenced scalar.
  function bounds(n: Node, v: unknown): boolean {
    if (n.$ref) {const key=n.$ref.split('/').pop()!;if (['Id','Revision','Epoch','Utc'].includes(key)) return validWriteDefinition(key,v);return bounds(definitions[key],v)}
    if (object(v) && n.properties && !Object.entries(n.properties).every(([k,z]) => !has(v,k) || bounds(z as Node,v[k]))) return false
    if (Array.isArray(v) && n.items && !v.every(x=>bounds(n.items,x))) return false
    if (n.anyOf) return n.anyOf.some((x:Node)=>matches(x,v)&&bounds(x,v))
    if (n.oneOf) return n.oneOf.some((x:Node)=>matches(x,v)&&bounds(x,v))
    return true
  }
  return bounds(definitions[name],value)
}
export function assertWriteDefinition(name: string,value: unknown): void { if(!validWriteDefinition(name,value))throw new Error('FINANCE_INVALID_RESPONSE') }
export function decodeWriteResponse(raw: ArrayBuffer): unknown { return parseFinanceJson(new TextDecoder('utf-8',{fatal:true}).decode(raw)) }
