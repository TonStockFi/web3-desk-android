cd /Volumes/projects/adr/web3-desk/
if [ "$1" == "once" ]; then
  rsync -avh --delete --exclude-from=".gitignore" ~/Desktop/projects/android/web3-desk/ /Volumes/projects/adr/web3-desk/
else
  rsync -avh --delete --exclude-from=".gitignore" ~/Desktop/projects/android/web3-desk/ /Volumes/projects/adr/web3-desk/
fi
