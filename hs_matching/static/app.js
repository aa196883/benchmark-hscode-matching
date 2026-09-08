const form = document.querySelector('#comparison-form');
const checkboxes = [...form.querySelectorAll('input[name="approaches"]')];
const submit = document.querySelector('#submit-button');
const updateSelection = () => { submit.disabled = !checkboxes.some(box => box.checked); };
checkboxes.forEach(box => box.addEventListener('change', updateSelection));
updateSelection();
document.querySelector('#example').addEventListener('click', () => {
  document.querySelector('#query').value = 'Short-sleeved knitted T-shirts, made of 100% cotton, for adults.';
  document.querySelector('#query').focus();
});
form.addEventListener('submit', event => {
  if (!checkboxes.some(box => box.checked)) { event.preventDefault(); return; }
  const rag = checkboxes.find(box => box.value === 'rag');
  const neighbours = document.querySelector('#retrieval_k');
  neighbours.setCustomValidity('');
  if (rag.checked && Number(neighbours.value) < Number(document.querySelector('#top_k').value)) {
    neighbours.setCustomValidity('Le nombre de voisins RAG doit être au moins égal au nombre de résultats.');
    neighbours.reportValidity(); event.preventDefault(); return;
  }
  submit.disabled = true;
  submit.textContent = 'Comparaison en cours…';
  document.querySelector('#loading').hidden = false;
  document.querySelector('.results')?.setAttribute('aria-busy', 'true');
  const start = Date.now();
  setInterval(() => { document.querySelector('#elapsed').textContent = Math.floor((Date.now() - start) / 1000); }, 1000);
});
form.addEventListener('input', () => document.querySelector('#retrieval_k').setCustomValidity(''));
window.addEventListener('pageshow', event => {
  if (event.persisted) window.location.reload();
  updateSelection();
});
