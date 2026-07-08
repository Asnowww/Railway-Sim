from .schemas import StepFleetRequest, step_fleet_request_from_dict


class InputMapper:
    def from_payload(self, payload: dict) -> StepFleetRequest:
        return step_fleet_request_from_dict(payload)
