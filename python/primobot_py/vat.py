from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal, ROUND_HALF_UP
from enum import Enum


class VatBasis(str, Enum):
    INCLUSIVE = "Gross (VAT Inclusive)"
    EXCLUSIVE = "Net (VAT Exclusive)"


@dataclass(frozen=True)
class VatResult:
    basis_label: str
    net_amount: Decimal
    vat_amount: Decimal
    gross_amount: Decimal


class VatCalculator:
    def calculate(self, amount: Decimal, vat_rate_percent: Decimal, basis: VatBasis) -> VatResult:
        if amount is None or vat_rate_percent is None or basis is None:
            raise ValueError("amount, vat_rate_percent, and basis are required")
        if amount < 0:
            raise ValueError("amount must be zero or greater")

        rate_fraction = vat_rate_percent / Decimal("100")

        if basis == VatBasis.INCLUSIVE:
            gross = self._round_money(amount)
            divisor = Decimal("1") + rate_fraction
            net = self._round_money(gross / divisor)
            vat = self._round_money(gross - net)
        else:
            net = self._round_money(amount)
            vat = self._round_money(net * rate_fraction)
            gross = self._round_money(net + vat)

        return VatResult(
            basis_label=basis.value,
            net_amount=net,
            vat_amount=vat,
            gross_amount=gross,
        )

    @staticmethod
    def _round_money(value: Decimal) -> Decimal:
        return value.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
