from .schemas import StepFleetResponse, step_fleet_response_to_dict


class OutputMapper:
    def to_payload(self, response: StepFleetResponse) -> dict:
        return step_fleet_response_to_dict(response)
