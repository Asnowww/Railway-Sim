import { request } from '../shared/api/client'
import type { PowerEnergyResponse, TrainEnergyResponse } from '../types/power'

export const energyApi = {
  trains: () => request<TrainEnergyResponse[]>('/energy/trains'),
  powerSections: () => request<PowerEnergyResponse>('/energy/power-sections')
}
