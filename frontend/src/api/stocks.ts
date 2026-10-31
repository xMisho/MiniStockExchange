import { getJson, isFiniteNumber, isRecord } from './client'
import type { StockMetadata } from '../types/market'

export async function getStocks(signal?: AbortSignal): Promise<StockMetadata[]> {
  const data = await getJson('/api/stocks', signal)
  if (!Array.isArray(data) || !data.every(isStock)) {
    throw new Error('The stock list response is invalid.')
  }
  if (new Set(data.map((stock) => stock.symbol)).size !== data.length) {
    throw new Error('The stock list contains duplicate symbols.')
  }
  return data
}

function isStock(value: unknown): value is StockMetadata {
  return isRecord(value) && typeof value.symbol === 'string' && value.symbol.trim().length > 0
    && typeof value.companyName === 'string' && value.companyName.trim().length > 0
    && isFiniteNumber(value.referencePrice) && value.referencePrice > 0
}
