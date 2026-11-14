import service, { get } from './request'

/** 城市定位结果 */
export interface WeatherLocation {
  code: string
  city: string
  province: string
}

/** 当前天气 */
export interface WeatherNow {
  temp: number
  weather: string
  high: number
  low: number
  humidity: string
  pm25: number | null
  quality: string
  aqi: number
  wind: string
  windLevel: string
  sunrise: string
  sunset: string
}

/** 单日预报 */
export interface WeatherDay {
  date: string
  week: string
  high: number
  low: number
  weather: string
  wind: string
  windLevel: string
  aqi: number
  notice: string
  sunrise: string
  sunset: string
}

/** 结构化预报 */
export interface WeatherForecast {
  location: WeatherLocation
  updateDate: string
  updateTime: string
  now: WeatherNow
  days: WeatherDay[]
}

export interface WeatherForecastResponse {
  forecast: WeatherForecast
  summary: string
}

/** Agent 调用轨迹 */
export interface WeatherTrace {
  name: string
  label: string
  args: string
  result: string
  ok: boolean
}

/** AI 建议 */
export interface WeatherAdvice {
  available: boolean
  answer: string
  message: string
  traces: WeatherTrace[]
  model: string
  elapsedMs: number
}

/** 城市实时天气 + 7 日预报（不调用模型） */
export function fetchWeatherForecast(city: string, days = 7) {
  return get<WeatherForecastResponse>('/tools/weather/forecast', { city, days })
}

/**
 * 让天气 Agent 查数据并给建议。
 *
 * <p>模型可能要跑两轮工具调用，默认 15 秒超时偏紧，这里单独放宽到 60 秒。</p>
 */
export function fetchWeatherAdvice(city: string, question = '') {
  return service.post('/tools/weather/agent', { city, question }, { timeout: 60000 }) as Promise<WeatherAdvice>
}
