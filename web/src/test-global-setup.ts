/** Every test runs in a zone far from UTC (+13 in November), so a date that round-trips through UTC shows up. */
export default function setup() {
  process.env.TZ = 'Pacific/Auckland'
}
