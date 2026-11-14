/** 45000 paise -> "₹450"; 45050 -> "₹450.50". Money stays an integer until this display step. */
export function formatPaise(paise: number): string {
  return new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    minimumFractionDigits: paise % 100 === 0 ? 0 : 2,
    maximumFractionDigits: 2,
  }).format(paise / 100);
}

export function formatDateTime(iso: string): string {
  return new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(iso));
}

/** 125 -> "2:05" */
export function formatCountdown(totalSeconds: number): string {
  const seconds = Math.max(0, totalSeconds);
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
}

export function seatName(seat: { section: string; row: string; number: number }): string {
  return `Section ${seat.section}, Row ${seat.row}, Seat ${seat.number}`;
}

export function rupeesToPaise(rupees: number): number {
  return Math.round(rupees * 100);
}

/** Parts of a date for a ticket-style "date stub": { weekday: "Sat", day: "21", month: "Nov", time: "6:30 pm" }. */
export function dateParts(iso: string) {
  const date = new Date(iso);
  const part = (options: Intl.DateTimeFormatOptions) => new Intl.DateTimeFormat('en-IN', options).format(date);
  return {
    weekday: part({ weekday: 'short' }),
    day: part({ day: 'numeric' }),
    month: part({ month: 'short' }),
    time: part({ hour: 'numeric', minute: '2-digit' }),
  };
}
