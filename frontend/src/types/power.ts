/** 供电域 REST 明细类型（快照内 PowerSectionState 见 types/simulation.ts）。 */

export interface SubstationDevice {
  id: string
  name?: string
  deviceType?: string
  state?: string
  available?: boolean
  affectsSectionIds?: string[]
  ratedCurrentAmps?: number
  [key: string]: unknown
}

export interface SubstationState {
  id: string
  name?: string
  supplyMode?: string
  availability?: string
  devices?: SubstationDevice[]
  sectionIds?: string[]
  dataQuality?: string
  updatedAt?: string
  [key: string]: unknown
}

export interface IsolatorState {
  id: string
  thirdRailSectionId?: string
  state?: string
  dataQuality?: string
  updatedAt?: string
  [key: string]: unknown
}

export interface StrayCurrentRisk {
  id?: string
  sectionId?: string
  cabinetState?: string
  polarizedPotentialVolts?: number
  riskLevel?: string
  riskReason?: string
  suggestedAction?: string
  dataQuality?: string
  updatedAt?: string
  [key: string]: unknown
}

export interface PowerEnergySection {
  id: string
  name?: string
  loadWatts?: number
  regenPowerWatts?: number
  absorbedRegenPowerWatts?: number
  unabsorbedRegenPowerWatts?: number
  dataQuality?: string
  [key: string]: unknown
}

export interface PowerEnergyResponse {
  totalLoadWatts?: number
  totalRegenPowerWatts?: number
  totalAbsorbedRegenPowerWatts?: number
  totalUnabsorbedRegenPowerWatts?: number
  statisticsWindow?: string
  dataQuality?: string
  updatedAt?: string
  sections?: PowerEnergySection[]
  [key: string]: unknown
}

export interface PowerMaintenanceLock {
  sectionId: string
  sectionName?: string
  maintenanceState?: string
  lockoutState?: string
  breakerStatus?: string
  status?: string
  updatedAt?: string
  [key: string]: unknown
}

export interface PowerExternalHealth {
  mode?: string
  heartbeatStatus?: string
  lastPacketAt?: string | null
  latencyMillis?: number
  dataQuality?: string
  [key: string]: unknown
}

export interface TrainEnergyResponse {
  trainId: string
  energyConsumedKwh?: number
  energyRegeneratedKwh?: number
  netEnergyKwh?: number
  consumedKwh?: number
  regeneratedKwh?: number
  netKwh?: number
  statisticsWindow?: string
  dataQuality?: string
  updatedAt?: string
  [key: string]: unknown
}
